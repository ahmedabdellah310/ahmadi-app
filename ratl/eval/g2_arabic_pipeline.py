"""G2 through the ARABIC pipeline: Uthmani text -> Normalizer -> tokenizer -> model.

The decisive measurement for SPEC 5.5. Exact string agreement with quran.com is
not the requirement; what matters is whether targets derived from Arabic align
as well as targets taken from the reference transliteration.

Everything except the target source is unchanged: same corpus, same ground
truth, same +/-150 ms tolerance, same 0.85 threshold, same frozen aligner and
M2E_edge_spike rule, same shipping ONNX artifact. 05_g2_alignment.py is imported
unmodified.

Two runs, so the difference is attributable:
    reference  targets from cached quran.com transliterations  (expect 0.8570)
    arabic     targets from Emran's quran.json via the Normalizer

The threshold is NOT adjusted. If the Arabic pipeline scores lower, that is the
result.
"""
import importlib.util
import io
import json
import pathlib
import sys
import time

import numpy as np

ROOT = pathlib.Path(__file__).resolve().parent.parent
SPIKE = ROOT / "spike"
sys.path.insert(0, str(SPIKE))
EMRAN_QURAN = pathlib.Path(
    r"C:\Android Projects\Emran\emran\app\src\main\assets\quran.json"
)


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
v2 = _load("v2", "verify_onnx_g2_e2e.py", ROOT / "models")
nz = _load("nz", "normalizer_probe.py", ROOT / "eval")

OUT = ROOT / "models" / "out"
MODEL = "ratl-align-v1-e2e-fp32.onnx"
REPORT = ROOT / "eval" / "g2_arabic_pipeline.json"
RULE = "M2E_edge_spike"
FROZEN = 0.8570


def arabic_targets():
    q = json.load(io.open(EMRAN_QURAN, encoding="utf-8"))
    return {(s["id"], v["id"]): v["text"] for s in q for v in s["verses"]}


def evaluate(tag, words_for, sess, gt):
    import librosa
    rows = []
    t0 = time.time()
    stoi = {c: i for i, c in enumerate(
        json.loads((OUT / "vocab.json").read_text(encoding="utf-8")))}
    for rec, (_, speed) in g2.RECITERS.items():
        for s, a in g2.AYAT:
            g = gt[rec].get((s, a))
            if not g:
                continue
            words = words_for(s, a)
            if not words:
                continue
            ids, spans, _ = e1.build_tokens_v2([{"tr": w} for w in words], stoi)
            if not ids:
                continue
            try:
                ap = g2.fetch_audio(rec, s, a)
                audio, _ = librosa.load(str(ap), sr=g2.SR, mono=True)
            except Exception:  # noqa: BLE001
                continue
            clog = v2.run_windows(audio, sess)
            T = clog.shape[0]
            tok = g2.ctc_forced_align(
                g2.log_softmax(clog.astype(np.float64)), ids, blank=0)
            gt_segs = {sg[0]: sg for sg in g["segments"]}
            for wi in range(len(spans)):
                seg = gt_segs.get(wi)
                if seg is None:
                    continue
                b = d3.word_bounds(RULE, spans, tok, wi, len(ids), T)
                if b is None:
                    continue
                rows.append({"speed": speed, "wdur": seg[3] - seg[2],
                             "es": b[0] * m1.MS_PER_FRAME - seg[2],
                             "ee": b[1] * m1.MS_PER_FRAME - seg[3]})
        print(f"      [{tag}] {rec:<34} ({time.time()-t0:.0f}s)", flush=True)

    es = np.array([r["es"] for r in rows])
    ee = np.array([r["ee"] for r in rows])
    ok_s = np.abs(es) <= g2.G2_TOLERANCE_MS
    ok_e = np.abs(ee) <= g2.G2_TOLERANCE_MS
    allok = np.concatenate([ok_s, ok_e])

    def sub(pred):
        i = [j for j, r in enumerate(rows) if pred(r)]
        return None if not i else round(
            float(np.mean(np.concatenate([ok_s[i], ok_e[i]]))), 4)

    return {
        "G2": round(float(allok.mean()), 4),
        "start_acc": round(float(ok_s.mean()), 4),
        "end_acc": round(float(ok_e.mean()), 4),
        "n_words": len(rows),
        "by_speed": {sp: sub(lambda r, sp=sp: r["speed"] == sp)
                     for sp in sorted({r["speed"] for r in rows})},
    }


def main():
    import onnxruntime as ort
    so = ort.SessionOptions()
    so.intra_op_num_threads = 1
    sess = ort.InferenceSession(str(OUT / MODEL), so, providers=["CPUExecutionProvider"])
    gt = g2.load_gt()

    ref_cache = {k: g2.fetch_translit(*k) for k in g2.AYAT}

    def reference_words(s, a):
        return [(w["tr"] or "").strip().lower() for w in ref_cache[(s, a)] if w.get("tr")]

    arabic = arabic_targets()

    def normalizer_words(s, a):
        text = arabic.get((s, a))
        if not text:
            return []
        return [nz.romanize(w, utterance_initial=(k == 0)).lower()
                for k, w in enumerate(text.split()) if w.strip()]

    res_ref = evaluate("reference", reference_words, sess, gt)
    res_ar = evaluate("arabic", normalizer_words, sess, gt)

    rep = {
        "step": "G2 through the Arabic Normalizer pipeline",
        "corpus": "RATL-Bench-v0 (unchanged)",
        "harness": "05_g2_alignment.py imported unmodified",
        "threshold": g2.G2_THRESHOLD,
        "frozen_reference_G2": FROZEN,
        "reference_transliteration": res_ref,
        "arabic_normalizer": res_ar,
        "delta_G2": round(res_ar["G2"] - res_ref["G2"], 4),
        "arabic_passes_spec": bool(res_ar["G2"] >= g2.G2_THRESHOLD),
        "note": "Threshold, tolerance, corpus, aligner and boundary rule are "
                "unchanged. Only the source of the target words differs.",
    }
    REPORT.write_text(json.dumps(rep, indent=2, ensure_ascii=False), encoding="utf-8")

    print("\n" + "=" * 70)
    print("G2: reference transliteration vs Arabic Normalizer")
    print("=" * 70)
    for k in ("reference_transliteration", "arabic_normalizer"):
        r = rep[k]
        print(f"  {k:<28} G2 {r['G2']:.4f}  start {r['start_acc']:.4f}  "
              f"end {r['end_acc']:.4f}  n={r['n_words']}")
    print(f"\n  delta                        {rep['delta_G2']:+.4f}")
    print(f"  arabic meets the 0.85 gate   {rep['arabic_passes_spec']}")


if __name__ == "__main__":
    main()
