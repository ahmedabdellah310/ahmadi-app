"""
RATL M1 - the gate run. RATL-Bench-v0, ONE configuration, chosen on held-out data.

Config selected by 20_m1_select.py on RATL-Val-v0 WITHOUT touching the bench:
    windowing = overlap (15 s window, 10 s hop, 2.5 s context margins discarded)
    rule      = M2E_edge_spike
    offset    = 0 ms   (every non-zero offset was worse on held-out)
    checkpoint= exp1

Ground truth, corpus, tolerance (150 ms) and threshold (0.85) are unchanged;
05_g2_alignment.py is imported unmodified and supplies all of them.

Both frame-time conventions are reported, because the selection script uses an
exact 50 fps clock while every earlier bench number used dur_ms/T:
    exact   t = frame * 20.0 ms
    scaled  t = frame * (duration_ms / n_frames)     <- prior convention
Neither is tuned; reporting both keeps this number comparable to Exp 1/1b/3.

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

RESULTS = HERE / "results"
CKPT = HERE / "models" / "exp1" / "best.pt"
RULE = "M2E_edge_spike"
OFFSET = 0.0

REF = {"wav2vec2": 0.5611, "exp1_spike": 0.6776, "exp1b_spike": 0.6707,
       "exp3_M2_nonoverlap": 0.7828, "m1_M2_overlap": 0.7889,
       "val_selected_M2E": 0.8787}


def main():
    import librosa

    dev = "cuda" if torch.cuda.is_available() else "cpu"
    ck = torch.load(CKPT, map_location=dev, weights_only=False)
    itos = ck["vocab"]
    stoi = {c: i for i, c in enumerate(itos)}
    model = tr.EncCTC(len(itos)).to(dev)
    model.load_state_dict(ck["model"])
    model.eval()

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
            logits = m1.logits_overlap(audio, model, fe, dev)
            T = logits.shape[0]
            dur_ms = len(audio) / g2.SR * 1000.0
            logp = g2.log_softmax(logits.astype(np.float64))
            tok = g2.ctc_forced_align(logp, ids, blank=0)
            gt_segs = {sg[0]: sg for sg in g["segments"]}
            for wi in range(len(spans)):
                seg = gt_segs.get(wi)
                if seg is None:
                    continue
                b = d3.word_bounds(RULE, spans, tok, wi, len(ids), T)
                if b is None:
                    continue
                for conv, mpf in (("exact", m1.MS_PER_FRAME),
                                  ("scaled", dur_ms / T)):
                    rows.append({"conv": conv, "reciter": rec, "speed": speed,
                                 "surah": s, "ayah": a, "word": wi,
                                 "err_start": b[0] * mpf - seg[2] - OFFSET,
                                 "err_end": b[1] * mpf - seg[3] - OFFSET})
        print(f"      {rec:<34} ({time.time()-t0:.0f}s)", flush=True)

    def summarise(conv):
        ws = [r for r in rows if r["conv"] == conv]
        es = np.array([r["err_start"] for r in ws])
        ee = np.array([r["err_end"] for r in ws])
        oks, oke = np.abs(es) <= g2.G2_TOLERANCE_MS, np.abs(ee) <= g2.G2_TOLERANCE_MS
        allerr = np.abs(np.concatenate([es, ee]))
        by_speed = {}
        for sp in sorted({r["speed"] for r in ws}):
            sub = [i for i, r in enumerate(ws) if r["speed"] == sp]
            by_speed[sp] = round(float(np.mean(
                np.concatenate([oks[sub], oke[sub]]))), 4)
        nm = [i for i, r in enumerate(ws) if r["speed"] != "very_slow"]
        G2 = float(np.mean(np.concatenate([oks, oke])))
        return {"G2": round(G2, 4), "pass_spec": bool(G2 >= g2.G2_THRESHOLD),
                "gap_to_spec": round(g2.G2_THRESHOLD - G2, 4),
                "start_acc": round(float(oks.mean()), 4),
                "end_acc": round(float(oke.mean()), 4),
                "G2_excl_mujawwad": round(float(np.mean(
                    np.concatenate([oks[nm], oke[nm]]))), 4),
                "median_signed_start_ms": round(float(np.median(es)), 1),
                "median_signed_end_ms": round(float(np.median(ee)), 1),
                "abs_err_ms": {
                    "median": round(float(np.percentile(allerr, 50)), 1),
                    "p90": round(float(np.percentile(allerr, 90)), 1),
                    "p99": round(float(np.percentile(allerr, 99)), 1),
                    "max": round(float(allerr.max()), 1)},
                "by_speed": by_speed, "n_words": len(ws)}

    out = {"stage": "M1_gate", "corpus": "RATL-Bench-v0 (unchanged)",
           "harness": "05_g2_alignment.py imported unmodified",
           "spec_threshold": g2.G2_THRESHOLD,
           "tolerance_ms": g2.G2_TOLERANCE_MS,
           "config": {"checkpoint": "exp1", "windowing": "overlap",
                      "win_s": m1.WIN_S, "hop_s": m1.HOP_S,
                      "rule": RULE, "offset_ms": OFFSET,
                      "selected_on": "RATL-Val-v0 (held-out)"},
           "reference": REF,
           "exact": summarise("exact"), "scaled": summarise("scaled")}
    (RESULTS / "21_m1_gate_rows.json").write_text(
        json.dumps([r for r in rows if r["conv"] == "exact"], ensure_ascii=False),
        encoding="utf-8")
    (RESULTS / "21_m1_gate.json").write_text(
        json.dumps(out, indent=2, ensure_ascii=False), encoding="utf-8")

    print("\n" + "=" * 76)
    print("M1 GATE - RATL-Bench-v0   (SPEC G2 >= 0.85, UNCHANGED)")
    print("=" * 76)
    for conv in ("exact", "scaled"):
        r = out[conv]
        print(f"\n[{conv} frame clock]")
        print(f"  G2 {r['G2']:.4f}   {'PASS' if r['pass_spec'] else 'FAIL'}"
              f"   gap {r['gap_to_spec']:+.4f}")
        print(f"  start {r['start_acc']:.4f}   end {r['end_acc']:.4f}   "
              f"excl-Mujawwad {r['G2_excl_mujawwad']:.4f}")
        print(f"  signed start {r['median_signed_start_ms']:+.1f} ms   "
              f"end {r['median_signed_end_ms']:+.1f} ms")
        print(f"  abs err {r['abs_err_ms']}")
        print(f"  by speed {json.dumps(r['by_speed'])}")
    print(f"\nreference: {json.dumps(REF)}")


if __name__ == "__main__":
    main()
