"""M2.0 - does the END-TO-END ONNX artifact reproduce the FROZEN G2 = 0.8570?

Same corpus, same ground truth, same tolerance, same forced aligner, same
M2E_edge_spike rule, same 15 s/10 s/2.5 s windowing - imported unmodified from
the spike. The only change is that the mel front end now lives inside the ONNX
graph, so the input is reflect-padded PCM rather than a precomputed log-mel.

NOT a tuning run. RATL-Bench-v0 is the gate and nothing here selects anything.
"""
import importlib.util
import json
import pathlib
import sys
import time

import numpy as np

ROOT = pathlib.Path(__file__).resolve().parent.parent
SPIKE = ROOT / "spike"
sys.path.insert(0, str(SPIKE))


def _load(name, fn, base=SPIKE):
    sp = importlib.util.spec_from_file_location(name, base / fn)
    m = importlib.util.module_from_spec(sp)
    sys.modules[name] = m
    sp.loader.exec_module(m)
    return m


g2 = _load("g2", "05_g2_alignment.py")
tr = _load("tr", "10_train_exp1.py")
e1 = _load("e1", "13_eval_exp1.py")
d3 = _load("d3", "18_decode_extent.py")
m1 = _load("m1", "20_m1_select.py")
mp2 = _load("mp2", "mel_path_spike2.py", ROOT / "models")

OUT = ROOT / "models" / "out"
RULE = "M2E_edge_spike"
REPORT = ROOT / "models" / "verify_onnx_g2_e2e.json"
FROZEN_G2 = 0.8570
N_SAMP = int(m1.WIN_S * m1.SR)


def prep(ch):
    """Zero-pad the chunk to the 15 s window, then reflect-pad for the STFT.

    This is exactly what the Kotlin core will do - the entire front end.
    """
    if len(ch) < N_SAMP:
        ch = np.pad(ch, (0, N_SAMP - len(ch)))
    return mp2.reflect_pad(ch.astype(np.float32)).astype(np.float32)[None]


def run_windows(audio, sess):
    win, hop = N_SAMP, int(m1.HOP_S * m1.SR)
    mfr = int(m1.MARGIN_S * m1.FPS)
    n = len(audio)

    def fwd(ch):
        return sess.run(["logits"], {"pcm_padded": prep(ch)})[0][0]

    if n <= win:
        c = fwd(audio)
        v = max(1, int(min(n / m1.SR * m1.FPS, tr.ENC_FRAMES)))
        return c[:v]
    out, start = [], 0
    while start < n:
        ch = audio[start:start + win]
        c = fwd(ch)
        v = max(1, int(min(len(ch) / m1.SR * m1.FPS, tr.ENC_FRAMES)))
        c = c[:v]
        lo = 0 if start == 0 else mfr
        hi = v if start + win >= n else v - mfr
        if hi > lo:
            out.append(c[lo:hi])
        if start + win >= n:
            break
        start += hop
    return np.concatenate(out, axis=0)


def evaluate(tag, sess, gt, translit, stoi):
    import librosa
    rows = []
    t0 = time.time()
    for rec, (_, speed) in g2.RECITERS.items():
        for s, a in g2.AYAT:
            g = gt[rec].get((s, a))
            if not g:
                continue
            try:
                ap = g2.fetch_audio(rec, s, a)
                audio, _ = librosa.load(str(ap), sr=g2.SR, mono=True)
            except Exception:  # noqa: BLE001
                continue
            ids, spans, _ = e1.build_tokens_v2(translit[(s, a)], stoi)
            if not ids:
                continue
            clog = run_windows(audio, sess)
            T = clog.shape[0]
            tok = g2.ctc_forced_align(g2.log_softmax(clog.astype(np.float64)), ids, blank=0)
            gt_segs = {sg[0]: sg for sg in g["segments"]}
            for wi in range(len(spans)):
                seg = gt_segs.get(wi)
                if seg is None:
                    continue
                b = d3.word_bounds(RULE, spans, tok, wi, len(ids), T)
                if b is None:
                    continue
                rows.append({"reciter": rec, "speed": speed, "surah": s, "ayah": a,
                             "word": wi, "wdur": seg[3] - seg[2],
                             "err_start": b[0] * m1.MS_PER_FRAME - seg[2],
                             "err_end": b[1] * m1.MS_PER_FRAME - seg[3]})
        print(f"      [{tag}] {rec:<34} ({time.time()-t0:.0f}s)", flush=True)

    es = np.array([r["err_start"] for r in rows])
    ee = np.array([r["err_end"] for r in rows])
    oks, oke = np.abs(es) <= g2.G2_TOLERANCE_MS, np.abs(ee) <= g2.G2_TOLERANCE_MS
    G2 = float(np.mean(np.concatenate([oks, oke])))

    def sub(pred):
        i = [j for j, r in enumerate(rows) if pred(r)]
        return None if not i else round(float(np.mean(np.concatenate([oks[i], oke[i]]))), 4)

    allerr = np.abs(np.concatenate([es, ee]))
    return {"G2": round(G2, 4), "pass_spec": bool(G2 >= g2.G2_THRESHOLD),
            "start_acc": round(float(oks.mean()), 4),
            "end_acc": round(float(oke.mean()), 4),
            "by_speed": {sp: sub(lambda r, sp=sp: r["speed"] == sp)
                         for sp in sorted({r["speed"] for r in rows})},
            "by_word_duration": {"<1s": sub(lambda r: r["wdur"] < 1000),
                                 "1-2s": sub(lambda r: 1000 <= r["wdur"] < 2000),
                                 "2-4s": sub(lambda r: 2000 <= r["wdur"] < 4000),
                                 ">4s": sub(lambda r: r["wdur"] >= 4000)},
            "G2_excl_mujawwad": sub(lambda r: r["speed"] != "very_slow"),
            "abs_err_ms": {"median": round(float(np.percentile(allerr, 50)), 1),
                           "p90": round(float(np.percentile(allerr, 90)), 1),
                           "p99": round(float(np.percentile(allerr, 99)), 1),
                           "max": round(float(allerr.max()), 1)},
            "n_words": len(rows)}, rows


def main():
    import onnxruntime as ort
    import torch

    ck = torch.load(SPIKE / "models" / "exp2" / "best.pt", map_location="cpu",
                    weights_only=False)
    stoi = {c: i for i, c in enumerate(ck["vocab"])}
    gt = g2.load_gt()
    translit = {k: g2.fetch_translit(*k) for k in g2.AYAT}

    so = ort.SessionOptions()
    so.intra_op_num_threads = 1
    res, rows = {}, {}
    for kind in ("fp32", "int8"):
        sess = ort.InferenceSession(str(OUT / f"ratl-align-v1-e2e-{kind}.onnx"), so,
                                    providers=["CPUExecutionProvider"])
        res[kind], rows[kind] = evaluate(f"e2e-{kind}", sess, gt, translit, stoi)

    prev = json.loads((ROOT / "models" / "verify_onnx_g2.json").read_text(encoding="utf-8"))
    key = {(r["reciter"], r["surah"], r["ayah"], r["word"]): r for r in rows["fp32"]}

    rep = {"step": "M2.0 verify_onnx_g2_e2e",
           "corpus": "RATL-Bench-v0 (unchanged)",
           "harness": "05_g2_alignment.py imported unmodified",
           "graph": "reflect-padded PCM -> logits, conv-basis mel front end in-graph",
           "frozen_reference_G2": FROZEN_G2,
           "spec_threshold": g2.G2_THRESHOLD,
           "results": res,
           "reproduces_frozen_G2": {k: abs(res[k]["G2"] - FROZEN_G2) < 1e-9 for k in res},
           "delta_vs_frozen": {k: round(res[k]["G2"] - FROZEN_G2, 4) for k in res},
           "staged_reference": {k: prev["results"][k]["G2"] for k in prev["results"]},
           "n_boundaries": len(rows["fp32"]) * 2}
    REPORT.write_text(json.dumps(rep, indent=2, ensure_ascii=False), encoding="utf-8")

    print("\n" + "=" * 72)
    print("END-TO-END ONNX vs FROZEN G2 = 0.8570   (threshold 0.85, unchanged)")
    print("=" * 72)
    for k in ("torch", "fp32", "int8"):
        if k in prev["results"]:
            print(f"  staged {k:<5} G2 {prev['results'][k]['G2']:.4f}")
    for k in res:
        print(f"  e2e    {k:<5} G2 {res[k]['G2']:.4f}  delta {res[k]['G2']-FROZEN_G2:+.4f}  "
              f"{'PASS' if res[k]['pass_spec'] else 'FAIL'}")
    del key


if __name__ == "__main__":
    main()
