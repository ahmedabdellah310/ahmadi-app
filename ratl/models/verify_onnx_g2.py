"""M2.0 - does the exported ONNX artifact reproduce the FROZEN G2 = 0.8570?

This is 25_exp2_gate.py with exactly one substitution: the torch forward pass is
replaced by an ONNX Runtime session. Ground truth, corpus, tolerance (150 ms),
threshold (0.85), windowing, forced aligner and boundary rule are all imported
unmodified from the spike, so any difference in the result is attributable to
the export and to nothing else.

Runs three backends over the same corpus so export error and quantisation error
are separable:
    torch  -> the frozen reference (must land on 0.8570)
    fp32   -> ONNX graph correctness
    int8   -> the shipping artifact

NOT a tuning run. Nothing here selects anything; RATL-Bench-v0 is the gate.
"""
import importlib.util
import json
import pathlib
import sys
import time

import numpy as np
import torch

ROOT = pathlib.Path(__file__).resolve().parent.parent
SPIKE = ROOT / "spike"
sys.path.insert(0, str(SPIKE))


def _load(name, fn):
    sp = importlib.util.spec_from_file_location(name, SPIKE / fn)
    m = importlib.util.module_from_spec(sp)
    sys.modules[name] = m
    sp.loader.exec_module(m)
    return m


g2 = _load("g2", "05_g2_alignment.py")      # FROZEN harness
tr = _load("tr", "10_train_exp1.py")
e1 = _load("e1", "13_eval_exp1.py")
d3 = _load("d3", "18_decode_extent.py")     # word_bounds / M2E_edge_spike
m1 = _load("m1", "20_m1_select.py")         # WIN_S / HOP_S / MARGIN_S / MS_PER_FRAME
x2 = _load("x2", "23_train_exp2.py")

OUT = ROOT / "models" / "out"
RULE = "M2E_edge_spike"
REPORT = ROOT / "models" / "verify_onnx_g2.json"
FROZEN_G2 = 0.8570


def build_fe():
    from transformers import WhisperFeatureExtractor
    fe = WhisperFeatureExtractor.from_pretrained(tr.BASE)
    fe.chunk_length = int(m1.WIN_S)
    fe.n_samples = int(m1.WIN_S * m1.SR)
    fe.nb_max_frames = tr.MEL_FRAMES
    return fe


def mel(fe, ch):
    f = fe(ch, sampling_rate=m1.SR, return_tensors="np", padding="max_length",
           max_length=int(m1.WIN_S * m1.SR))["input_features"][0][:, :tr.MEL_FRAMES]
    if f.shape[1] < tr.MEL_FRAMES:
        f = np.pad(f, ((0, 0), (0, tr.MEL_FRAMES - f.shape[1])), constant_values=f.min())
    return f[None].astype(np.float32)


def run_windows(audio, fwd, fe):
    """FROZEN geometry: 15 s window, 10 s hop, 2.5 s margins discarded.

    Identical arithmetic to 24_exp2_select.run_windows, CTC head only.
    """
    win, hop = int(m1.WIN_S * m1.SR), int(m1.HOP_S * m1.SR)
    mfr = int(m1.MARGIN_S * m1.FPS)
    n = len(audio)
    if n <= win:
        c = fwd(mel(fe, audio))
        v = max(1, int(min(n / m1.SR * m1.FPS, tr.ENC_FRAMES)))
        return c[:v]
    out, start = [], 0
    while start < n:
        ch = audio[start:start + win]
        c = fwd(mel(fe, ch))
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


def evaluate(tag, fwd, fe, gt, translit, stoi, keep_logits=None):
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
            words = translit[(s, a)]
            ids, spans, _ = e1.build_tokens_v2(words, stoi)
            if not ids:
                continue
            clog = run_windows(audio, fwd, fe)
            if keep_logits is not None:
                keep_logits[(rec, s, a)] = clog
            T = clog.shape[0]
            logp = g2.log_softmax(clog.astype(np.float64))
            tok = g2.ctc_forced_align(logp, ids, blank=0)
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
    return {
        "G2": round(G2, 4), "pass_spec": bool(G2 >= g2.G2_THRESHOLD),
        "start_acc": round(float(oks.mean()), 4), "end_acc": round(float(oke.mean()), 4),
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
        "n_words": len(rows),
    }, rows


def main():
    import onnxruntime as ort

    ck = torch.load(SPIKE / "models" / "exp2" / "best.pt", map_location="cpu",
                    weights_only=False)
    itos = ck["vocab"]
    stoi = {c: i for i, c in enumerate(itos)}

    fe = build_fe()
    gt = g2.load_gt()
    translit = {k: g2.fetch_translit(*k) for k in g2.AYAT}

    full = x2.EncCTCBoundary(len(itos))
    full.load_state_dict(ck["model"])
    full.eval()

    def fwd_torch(x):
        with torch.no_grad():
            return full(torch.from_numpy(x))[0][0].float().numpy()

    so = ort.SessionOptions()
    so.intra_op_num_threads = 1
    sess = {k: ort.InferenceSession(str(OUT / f"ratl-align-v1-{k}.onnx"), so,
                                    providers=["CPUExecutionProvider"])
            for k in ("fp32", "int8")}

    def mk(k):
        return lambda x: sess[k].run(["logits"], {"input_features": x})[0][0]

    logits = {}
    res = {}
    res["torch"], rows_t = evaluate("torch", fwd_torch, fe, gt, translit, stoi,
                                    keep_logits=logits.setdefault("torch", {}))
    res["fp32"], _ = evaluate("fp32", mk("fp32"), fe, gt, translit, stoi,
                              keep_logits=logits.setdefault("fp32", {}))
    res["int8"], rows_8 = evaluate("int8", mk("int8"), fe, gt, translit, stoi,
                                   keep_logits=logits.setdefault("int8", {}))

    # ---- logit-level divergence over the whole bench, not just probe clips
    def diverge(a, b):
        ds, agree, n = [], 0, 0
        for k in a:
            if k not in b:
                continue
            x, y = a[k], b[k]
            t = min(len(x), len(y))
            ds.append(float(np.abs(x[:t] - y[:t]).max()))
            agree += int((x[:t].argmax(-1) == y[:t].argmax(-1)).sum())
            n += t
        return {"max_abs": round(max(ds), 6), "mean_of_per_clip_max": round(float(np.mean(ds)), 6),
                "argmax_agreement": round(agree / n, 6), "frames": n}

    # ---- per-word boundary agreement torch vs int8
    key = {(r["reciter"], r["surah"], r["ayah"], r["word"]): r for r in rows_t}
    dstart, dend = [], []
    for r in rows_8:
        k = (r["reciter"], r["surah"], r["ayah"], r["word"])
        if k in key:
            dstart.append(abs(r["err_start"] - key[k]["err_start"]))
            dend.append(abs(r["err_end"] - key[k]["err_end"]))
    dall = np.array(dstart + dend)

    rep = {
        "step": "M2.0 verify_onnx_g2",
        "corpus": "RATL-Bench-v0 (unchanged)",
        "harness": "05_g2_alignment.py imported unmodified",
        "decode": "M2E_edge_spike + frozen 15 s/10 s/2.5 s overlap",
        "frozen_reference_G2": FROZEN_G2,
        "spec_threshold": g2.G2_THRESHOLD,
        "results": res,
        "reproduces_frozen_G2": {
            "torch": abs(res["torch"]["G2"] - FROZEN_G2) < 1e-9,
            "fp32": abs(res["fp32"]["G2"] - FROZEN_G2) < 1e-9,
            "int8": abs(res["int8"]["G2"] - FROZEN_G2) < 1e-9,
        },
        "delta_vs_frozen": {k: round(res[k]["G2"] - FROZEN_G2, 4) for k in res},
        "logit_divergence": {
            "torch_vs_fp32": diverge(logits["torch"], logits["fp32"]),
            "torch_vs_int8": diverge(logits["torch"], logits["int8"]),
        },
        "boundary_divergence_torch_vs_int8_ms": {
            "n_boundaries": int(dall.size),
            "identical": int((dall == 0).sum()),
            "identical_frac": round(float((dall == 0).mean()), 6),
            "max": round(float(dall.max()), 1),
            "p99": round(float(np.percentile(dall, 99)), 1),
            "mean": round(float(dall.mean()), 3),
        },
    }
    REPORT.write_text(json.dumps(rep, indent=2, ensure_ascii=False), encoding="utf-8")

    print("\n" + "=" * 72)
    print("ONNX PARITY vs FROZEN G2 = 0.8570   (SPEC threshold 0.85, unchanged)")
    print("=" * 72)
    for k in ("torch", "fp32", "int8"):
        r = res[k]
        print(f"  {k:<6} G2 {r['G2']:.4f}  delta {r['G2']-FROZEN_G2:+.4f}  "
              f"start {r['start_acc']:.4f}  end {r['end_acc']:.4f}  "
              f"{'PASS' if r['pass_spec'] else 'FAIL'}")
    b = rep["boundary_divergence_torch_vs_int8_ms"]
    print(f"\n  boundaries identical torch vs int8: {b['identical']}/{b['n_boundaries']} "
          f"({b['identical_frac']*100:.2f}%)  max drift {b['max']} ms")


if __name__ == "__main__":
    main()
