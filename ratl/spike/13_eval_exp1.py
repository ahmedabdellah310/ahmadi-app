"""
RATL Rung 3 — Experiment 1 evaluation against G2.

IDENTICAL EVALUATION CONDITIONS: this script IMPORTS the alignment algorithm,
ground-truth loader, corpus definition (AYAT / RECITERS), tolerance and metric
code from 05_g2_alignment.py. That file is NOT modified. Only the acoustic
frontend differs, because the candidate takes log-mel input rather than raw
waveform -- which is the whole point of the candidate.

Reports word-end accuracy vs the MEASURED 44.0% baseline and G2 vs 56.11%.
Falsification criteria were fixed in RUNG3_PLAN.md BEFORE training.
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

spec = importlib.util.spec_from_file_location("g2", HERE / "05_g2_alignment.py")
g2 = importlib.util.module_from_spec(spec)
sys.modules["g2"] = g2
spec.loader.exec_module(g2)

spec2 = importlib.util.spec_from_file_location("tr", HERE / "10_train_exp1.py")
tr = importlib.util.module_from_spec(spec2)
sys.modules["tr"] = tr
spec2.loader.exec_module(tr)

RESULTS = HERE / "results"
CKPT = HERE / "models" / "exp1" / "best.pt"

# Baselines -- MEASURED, from DAY3_NOTES.md
BASE_G2 = 0.5611
BASE_END = 0.4403
BASE_START = 0.6820


def build_tokens_v2(words, stoi):
    """Same contract as g2.build_tokens, new vocab, with explicit '|' separators."""
    ids, spans, oov = [], [], []
    for wi, w in enumerate(words):
        t = (w["tr"] or "").strip().lower()
        if not t:
            spans.append(None)
            continue
        if ids:
            ids.append(stoi["|"])
        start = len(ids)
        for ch in t:
            if ch in stoi:
                ids.append(stoi[ch])
            else:
                oov.append(ch)
        spans.append((start, len(ids)))
    return ids, spans, oov


def main():
    dev = "cuda" if torch.cuda.is_available() else "cpu"
    ck = torch.load(CKPT, map_location=dev, weights_only=False)
    itos = ck["vocab"]
    stoi = {c: i for i, c in enumerate(itos)}
    model = tr.EncCTC(len(itos)).to(dev)
    model.load_state_dict(ck["model"])
    model.eval()
    print(f"[model] epoch {ck['epoch']}  val_ctc {ck['val_ctc']:.4f}  vocab {len(itos)}")

    from transformers import WhisperFeatureExtractor

    fe = WhisperFeatureExtractor.from_pretrained(tr.BASE)
    fe.chunk_length = int(tr.WINDOW_S)
    fe.n_samples = int(tr.WINDOW_S * tr.SR)
    fe.nb_max_frames = tr.MEL_FRAMES

    import librosa

    gt = g2.load_gt()
    translit = {k: g2.fetch_translit(*k) for k in g2.AYAT}

    per_word, per_clip, oov_all = [], [], []
    t_start = time.time()

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
            ids, spans, oov = build_tokens_v2(words, stoi)
            oov_all.extend(oov)
            if not ids:
                continue

            dur_ms = len(audio) / g2.SR * 1000.0

            # 15 s non-overlapping windows; logits concatenated.
            win = int(tr.WINDOW_S * g2.SR)
            chunks = [audio[i:i + win] for i in range(0, max(1, len(audio)), win)]
            logit_parts = []
            t0 = time.time()
            for ch in chunks:
                f = fe(ch, sampling_rate=g2.SR, return_tensors="np",
                       padding="max_length", max_length=win)["input_features"][0]
                f = f[:, :tr.MEL_FRAMES]
                if f.shape[1] < tr.MEL_FRAMES:
                    f = np.pad(f, ((0, 0), (0, tr.MEL_FRAMES - f.shape[1])),
                               constant_values=f.min())
                x = torch.from_numpy(f[None].astype(np.float32)).to(dev)
                with torch.no_grad():
                    lg = model(x)[0].float().cpu().numpy()
                valid = int(min(len(ch) / g2.SR * 50, tr.ENC_FRAMES))
                logit_parts.append(lg[:max(1, valid)])
            infer_s = time.time() - t0
            logits = np.concatenate(logit_parts, axis=0)

            T = logits.shape[0]
            ms_per_frame = dur_ms / T
            logp = g2.log_softmax(logits.astype(np.float64))
            tok_spans = g2.ctc_forced_align(logp, ids, blank=0)

            gt_segs = {seg[0]: seg for seg in g["segments"]}
            n_ok_s = n_ok_e = n_cmp = 0
            for wi, sp in enumerate(spans):
                seg = gt_segs.get(wi)
                if sp is None or seg is None:
                    continue
                lo, hi = sp
                a_lo, a_hi = tok_spans[lo], tok_spans[hi - 1]
                if a_lo is None or a_hi is None:
                    continue
                p_start = a_lo[0] * ms_per_frame
                p_end = (a_hi[1] + 1) * ms_per_frame
                e_s, e_e = p_start - seg[2], p_end - seg[3]
                t = (words[wi]["tr"] or "").lower()
                per_word.append({
                    "reciter": rec, "speed": speed, "surah": s, "ayah": a, "word": wi,
                    "tr": t, "err_start": round(e_s, 1), "err_end": round(e_e, 1),
                    "ok_start": abs(e_s) <= g2.G2_TOLERANCE_MS,
                    "ok_end": abs(e_e) <= g2.G2_TOLERANCE_MS,
                    "has_q": "q" in t, "has_hdot": "ḥ" in t,
                })
                n_cmp += 1
                n_ok_s += abs(e_s) <= g2.G2_TOLERANCE_MS
                n_ok_e += abs(e_e) <= g2.G2_TOLERANCE_MS

            per_clip.append({"reciter": rec, "speed": speed, "surah": s, "ayah": a,
                             "duration_s": round(dur_ms / 1000, 2), "n_words": n_cmp,
                             "rtf": round(infer_s / (dur_ms / 1000), 3),
                             "acc_start": round(n_ok_s / n_cmp, 4) if n_cmp else None,
                             "acc_end": round(n_ok_e / n_cmp, 4) if n_cmp else None})
        print(f"      {rec:<34} done ({time.time() - t_start:.0f}s)")

    ok_all = [w["ok_start"] for w in per_word] + [w["ok_end"] for w in per_word]
    G2 = float(np.mean(ok_all))
    acc_s = float(np.mean([w["ok_start"] for w in per_word]))
    acc_e = float(np.mean([w["ok_end"] for w in per_word]))
    errs = np.abs(np.array([w["err_start"] for w in per_word] +
                           [w["err_end"] for w in per_word]))

    def sub(pred):
        ws = [w for w in per_word if pred(w)]
        o = [w["ok_start"] for w in ws] + [w["ok_end"] for w in ws]
        return {"n": len(ws), "acc": round(float(np.mean(o)), 4) if o else None}

    by_speed = {sp: sub(lambda w, sp=sp: w["speed"] == sp)
                for sp in {c["speed"] for c in per_clip}}

    # Falsification criteria, fixed in RUNG3_PLAN.md before training.
    if acc_e >= 0.70 and G2 >= 0.75:
        verdict = "H1_SUPPORTED"
    elif acc_e >= 0.55:
        verdict = "H1_PARTIAL -> run Experiment 2 (boundary head)"
    else:
        verdict = "H1_FALSIFIED"

    summary = {
        "step": "13_eval_exp1", "gate": "G2",
        "spec_threshold": g2.G2_THRESHOLD, "tolerance_ms": g2.G2_TOLERANCE_MS,
        "harness": "imported unmodified from 05_g2_alignment.py",
        "corpus": "RATL-Bench-v0 (unchanged)",
        "n_clips": len(per_clip), "n_words": len(per_word),
        "G2_measured": round(G2, 4), "G2_pass_spec": G2 >= g2.G2_THRESHOLD,
        "start_acc": round(acc_s, 4), "end_acc": round(acc_e, 4),
        "baseline": {"G2": BASE_G2, "start": BASE_START, "end": BASE_END},
        "delta": {"G2": round(G2 - BASE_G2, 4), "start": round(acc_s - BASE_START, 4),
                  "end": round(acc_e - BASE_END, 4)},
        "abs_err_ms": {"mean": round(float(errs.mean()), 1),
                       "median": round(float(np.percentile(errs, 50)), 1),
                       "p90": round(float(np.percentile(errs, 90)), 1),
                       "p99": round(float(np.percentile(errs, 99)), 1),
                       "max": round(float(errs.max()), 1)},
        "by_speed": by_speed,
        "words_with_q": sub(lambda w: w["has_q"]),
        "words_with_hdot": sub(lambda w: w["has_hdot"]),
        "oov_count": len(oov_all), "oov_chars": sorted(set(oov_all)),
        "mean_rtf_gpu_or_cpu": round(float(np.mean([c["rtf"] for c in per_clip])), 4),
        "VERDICT": verdict,
    }
    (RESULTS / "13_eval_exp1.json").write_text(
        json.dumps({"summary": summary, "per_clip": per_clip}, indent=2, ensure_ascii=False),
        encoding="utf-8")
    (RESULTS / "13_eval_exp1_per_word.json").write_text(
        json.dumps(per_word, indent=2, ensure_ascii=False), encoding="utf-8")

    print("\n===== EXPERIMENT 1 — G2 =====")
    for k, v in summary.items():
        print(f"  {k}: {v}")


if __name__ == "__main__":
    main()
