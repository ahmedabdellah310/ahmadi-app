"""
RATL Experiment 2 - gate run. RATL-Bench-v0, ONE configuration, selected on held-out.

Selected by 24_exp2_select.py on RATL-Val-v0 without touching the bench:
    checkpoint = exp2 (CTC + auxiliary boundary head, multi-task)
    decode     = V_m2e  -- M2E_edge_spike over the CTC head, overlap windowing
    val G2     = 0.8941  (M1 incumbent on the same held-out set: 0.8787)

The boundary head's own DP decode variants all lost on held-out data and are NOT
used. The head's contribution here is as an auxiliary TRAINING objective that
sharpened the encoder, not as a decode-time boundary source.

Ground truth, corpus, tolerance (150 ms) and threshold (0.85) unchanged;
05_g2_alignment.py imported unmodified.

THROWAWAY SPIKE CODE. Not RATL Core.
"""

import importlib.util
import json
import pathlib
import sys
import time

import numpy as np
import torch

HERE = pathlib.Path(__file__).parent
sys.path.insert(0, str(HERE))


def _load(name, path):
    spec = importlib.util.spec_from_file_location(name, HERE / path)
    mod = importlib.util.module_from_spec(spec)
    sys.modules[name] = mod
    spec.loader.exec_module(mod)
    return mod


g2 = _load("g2", "05_g2_alignment.py")     # FROZEN
tr = _load("tr", "10_train_exp1.py")
e1 = _load("e1", "13_eval_exp1.py")
d3 = _load("d3", "18_decode_extent.py")
m1 = _load("m1", "20_m1_select.py")
x2 = _load("x2", "23_train_exp2.py")
s2 = _load("s2", "24_exp2_select.py")

RESULTS = HERE / "results"
CKPT = HERE / "models" / "exp2" / "best.pt"
RULE = "M2E_edge_spike"

REF = {"wav2vec2": 0.5611, "exp1": 0.6776, "exp1b": 0.6707,
       "exp3_M2": 0.7828, "M1_M2E": 0.8395,
       "exp2_val_selected": 0.8941, "M1_val": 0.8787}


def main():
    import librosa

    dev = "cuda" if torch.cuda.is_available() else "cpu"
    ck = torch.load(CKPT, map_location=dev, weights_only=False)
    itos = ck["vocab"]
    stoi = {c: i for i, c in enumerate(itos)}
    model = x2.EncCTCBoundary(len(itos)).to(dev)
    model.load_state_dict(ck["model"])
    model.eval()
    print(f"[model] exp2 epoch {ck['epoch']}  val {ck['val']:.4f}")

    from transformers import WhisperFeatureExtractor

    fe = WhisperFeatureExtractor.from_pretrained(tr.BASE)
    fe.chunk_length = int(m1.WIN_S)
    fe.n_samples = int(m1.WIN_S * m1.SR)
    fe.nb_max_frames = tr.MEL_FRAMES

    gt = g2.load_gt()
    translit = {k: g2.fetch_translit(*k) for k in g2.AYAT}

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
            clog, blog = s2.run_windows(audio, model, fe, dev)
            T = clog.shape[0]
            logp = g2.log_softmax(clog.astype(np.float64))
            tok = g2.ctc_forced_align(logp, ids, blank=0)
            gt_segs = {sg[0]: sg for sg in g["segments"]}
            conf = None
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
            del conf
        print(f"      {rec:<34} ({time.time()-t0:.0f}s)", flush=True)

    es = np.array([r["err_start"] for r in rows])
    ee = np.array([r["err_end"] for r in rows])
    oks, oke = np.abs(es) <= g2.G2_TOLERANCE_MS, np.abs(ee) <= g2.G2_TOLERANCE_MS
    G2 = float(np.mean(np.concatenate([oks, oke])))
    allerr = np.abs(np.concatenate([es, ee]))

    def sub(pred):
        i = [j for j, r in enumerate(rows) if pred(r)]
        if not i:
            return None
        return round(float(np.mean(np.concatenate([oks[i], oke[i]]))), 4)

    by_speed = {sp: sub(lambda r, sp=sp: r["speed"] == sp)
                for sp in sorted({r["speed"] for r in rows})}
    by_dur = {"<1s": sub(lambda r: r["wdur"] < 1000),
              "1-2s": sub(lambda r: 1000 <= r["wdur"] < 2000),
              "2-4s": sub(lambda r: 2000 <= r["wdur"] < 4000),
              ">4s": sub(lambda r: r["wdur"] >= 4000)}

    out = {"stage": "exp2_gate", "corpus": "RATL-Bench-v0 (unchanged)",
           "harness": "05_g2_alignment.py imported unmodified",
           "spec_threshold": g2.G2_THRESHOLD, "tolerance_ms": g2.G2_TOLERANCE_MS,
           "config": {"checkpoint": "exp2 (CTC + boundary head)",
                      "decode": "M2E_edge_spike + overlap",
                      "selected_on": "RATL-Val-v0 (held-out)"},
           "G2": round(G2, 4), "pass_spec": bool(G2 >= g2.G2_THRESHOLD),
           "gap_to_spec": round(g2.G2_THRESHOLD - G2, 4),
           "start_acc": round(float(oks.mean()), 4),
           "end_acc": round(float(oke.mean()), 4),
           "by_speed": by_speed, "by_word_duration": by_dur,
           "G2_excl_mujawwad": sub(lambda r: r["speed"] != "very_slow"),
           "abs_err_ms": {"median": round(float(np.percentile(allerr, 50)), 1),
                          "p90": round(float(np.percentile(allerr, 90)), 1),
                          "p99": round(float(np.percentile(allerr, 99)), 1),
                          "max": round(float(allerr.max()), 1)},
           "n_words": len(rows), "reference": REF}
    (RESULTS / "25_exp2_gate.json").write_text(
        json.dumps(out, indent=2, ensure_ascii=False), encoding="utf-8")
    (RESULTS / "25_exp2_gate_rows.json").write_text(
        json.dumps(rows, ensure_ascii=False), encoding="utf-8")

    print("\n" + "=" * 76)
    print("EXPERIMENT 2 GATE - RATL-Bench-v0   (SPEC G2 >= 0.85, UNCHANGED)")
    print("=" * 76)
    print(f"  G2 {out['G2']:.4f}   {'*** PASS ***' if out['pass_spec'] else 'FAIL'}"
          f"   gap {out['gap_to_spec']:+.4f}")
    print(f"  start {out['start_acc']:.4f}   end {out['end_acc']:.4f}   "
          f"excl-Mujawwad {out['G2_excl_mujawwad']:.4f}")
    print(f"  by speed    {json.dumps(by_speed)}")
    print(f"  by word dur {json.dumps(by_dur)}")
    print(f"  abs err     {json.dumps(out['abs_err_ms'])}")
    print(f"\n  reference   M1 0.8395 | exp1 0.6776 | wav2vec2 0.5611")


if __name__ == "__main__":
    main()
