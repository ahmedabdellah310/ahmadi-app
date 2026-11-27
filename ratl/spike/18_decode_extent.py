"""
RATL Rung 3 - Experiment 3: decode-side word-extent recovery.

NO new training. NO new data. NO change to RATL-Bench-v0, the ground truth, the
tolerance, or the 0.85 threshold. 05_g2_alignment.py is imported unmodified and
supplies the corpus, ground truth, Viterbi forced aligner and metrics.

THE DEFECT (measured, EXP1B_RESULTS.md section 3):
  CTC places each character on a sharp spike surrounded by blanks. It is never
  asked where a word acoustically starts or ends. So predicted spans are
  systematically TOO NARROW -- starts land late (+71.7 ms median, 89% late),
  ends land early (-97.9 ms median, 77% early) -- and training the objective
  harder makes the spikes peakier and the spans narrower still.

THE IDEA:
  The frames between two token spikes are blank-labelled but acoustically belong
  to one of the two neighbouring units. Recovering that blank run into the word
  span should widen boundaries in exactly the direction the errors point.

FOUR BOUNDARY RULES, one forward pass, identical logits and identical Viterbi
path. Only the arithmetic that turns token spans into word boundaries differs:

  M0  spike      current method (baseline, must reproduce Exp 1b exactly)
  M1  half-gap   expand each side to the MIDPOINT of the adjacent blank run
  M2  separator  expand to the trained '|' word-separator spike
  M3  full-gap   expand to the adjacent token spike (maximal extent)

M0/M1/M3 bracket the space at 0%, 50%, 100% of the gap with NO free parameter,
so nothing is tuned against the bench. M2 is the linguistically motivated rule:
'|' was trained explicitly to model inter-word gaps.

SELECTION CAVEAT, declared up front: picking a winner on RATL-Bench-v0 is
selection on the evaluation set. Whichever rule wins must be confirmed on
held-out reciters before it is trusted as a V1 number.

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


g2 = _load("g2", "05_g2_alignment.py")          # FROZEN
tr = _load("tr", "10_train_exp1.py")            # architecture
e1 = _load("e1", "13_eval_exp1.py")             # build_tokens_v2

RESULTS = HERE / "results"
METHODS = ["M0_spike", "M1_half_gap", "M2_separator", "M2E_edge_spike", "M3_full_gap"]

CKPTS = {
    "exp1":  HERE / "models" / "exp1" / "best.pt",
    "exp1b": HERE / "models" / "exp1b" / "best.pt",
}

# MEASURED reference points
REF = {
    "wav2vec2": {"G2": 0.5611, "start": 0.6820, "end": 0.4403},
    "exp1":     {"G2": 0.6776, "start": 0.7882, "end": 0.5670},
    "exp1b":    {"G2": 0.6707, "start": 0.8079, "end": 0.5335},
}


def word_bounds(method, spans, tok_spans, wi, n_tok, T):
    """Frame-domain (start, end) for word wi under one boundary rule.

    spans[wi] = (lo, hi) token-index range of the word's characters in `ids`.
    A '|' separator token sits at index lo-1 and at index hi when present.
    """
    sp = spans[wi]
    if sp is None:
        return None
    lo, hi = sp
    a_lo, a_hi = tok_spans[lo], tok_spans[hi - 1]
    if a_lo is None or a_hi is None:
        return None
    raw_s = float(a_lo[0])
    raw_e = float(a_hi[1] + 1)

    if method == "M0_spike":
        return raw_s, raw_e

    # nearest token on each side (the '|' separator, when one exists)
    prev_e = 0.0
    if lo > 0 and tok_spans[lo - 1] is not None:
        prev_e = float(tok_spans[lo - 1][1] + 1)
    next_s = float(T)
    if hi < n_tok and tok_spans[hi] is not None:
        next_s = float(tok_spans[hi][0])

    if method == "M1_half_gap":
        return (prev_e + raw_s) / 2.0, (raw_e + next_s) / 2.0
    if method == "M2_separator":
        # start at the END of the preceding separator, end at the START of the
        # following one: the word owns everything between the two gap markers.
        return prev_e, next_s
    if method == "M2E_edge_spike":
        # M2 uses the trained '|' separators, which exist only BETWEEN words.
        # At an ayah edge there is no separator, and plain M2 falls back to
        # frame 0 / frame T -- so the first and last word absorb all leading and
        # trailing silence (measured: first-word start -400 ms, last-word end
        # +550 ms, acc 0.56/0.52 against 0.85 for interior words).
        # Where no separator exists, trust the spike instead.
        st = raw_s if lo == 0 else prev_e
        en = raw_e if hi >= n_tok else next_s
        return st, en
    if method == "M3_full_gap":
        s = float(tok_spans[lo - 1][0]) if (lo > 0 and tok_spans[lo - 1] is not None) else 0.0
        e = float(tok_spans[hi][1] + 1) if (hi < n_tok and tok_spans[hi] is not None) else float(T)
        return s, e
    raise ValueError(method)


def evaluate(tag, ckpt_path):
    dev = "cuda" if torch.cuda.is_available() else "cpu"
    ck = torch.load(ckpt_path, map_location=dev, weights_only=False)
    itos = ck["vocab"]
    stoi = {c: i for i, c in enumerate(itos)}
    model = tr.EncCTC(len(itos)).to(dev)
    model.load_state_dict(ck["model"])
    model.eval()
    print(f"\n[{tag}] epoch {ck['epoch']}  val_ctc {ck['val_ctc']:.4f}", flush=True)

    from transformers import WhisperFeatureExtractor

    fe = WhisperFeatureExtractor.from_pretrained(tr.BASE)
    fe.chunk_length = int(tr.WINDOW_S)
    fe.n_samples = int(tr.WINDOW_S * tr.SR)
    fe.nb_max_frames = tr.MEL_FRAMES

    import librosa

    gt = g2.load_gt()
    translit = {k: g2.fetch_translit(*k) for k in g2.AYAT}

    rows = {m: [] for m in METHODS}
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
            ids, spans, _oov = e1.build_tokens_v2(words, stoi)
            if not ids:
                continue

            dur_ms = len(audio) / g2.SR * 1000.0
            win = int(tr.WINDOW_S * g2.SR)
            parts = []
            for i in range(0, max(1, len(audio)), win):
                ch = audio[i:i + win]
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
                parts.append(lg[:max(1, valid)])
            logits = np.concatenate(parts, axis=0)

            T = logits.shape[0]
            ms_per_frame = dur_ms / T
            logp = g2.log_softmax(logits.astype(np.float64))
            tok_spans = g2.ctc_forced_align(logp, ids, blank=0)   # ONE path, shared

            gt_segs = {seg[0]: seg for seg in g["segments"]}
            for wi in range(len(spans)):
                seg = gt_segs.get(wi)
                if seg is None:
                    continue
                for m in METHODS:
                    b = word_bounds(m, spans, tok_spans, wi, len(ids), T)
                    if b is None:
                        continue
                    e_s = b[0] * ms_per_frame - seg[2]
                    e_e = b[1] * ms_per_frame - seg[3]
                    rows[m].append({
                        "reciter": rec, "speed": speed, "surah": s, "ayah": a,
                        "word": wi, "err_start": e_s, "err_end": e_e,
                        "ok_start": abs(e_s) <= g2.G2_TOLERANCE_MS,
                        "ok_end": abs(e_e) <= g2.G2_TOLERANCE_MS,
                    })
        print(f"      {rec:<34} ({time.time() - t0:.0f}s)", flush=True)
    return rows


def summarise(rows):
    out = {}
    for m, ws in rows.items():
        if not ws:
            continue
        oks = [w["ok_start"] for w in ws]
        oke = [w["ok_end"] for w in ws]
        es = np.array([w["err_start"] for w in ws])
        ee = np.array([w["err_end"] for w in ws])
        allerr = np.abs(np.concatenate([es, ee]))
        by_speed = {}
        for sp in sorted({w["speed"] for w in ws}):
            sub = [w for w in ws if w["speed"] == sp]
            by_speed[sp] = round(float(np.mean([w["ok_start"] for w in sub] +
                                               [w["ok_end"] for w in sub])), 4)
        nonmj = [w for w in ws if w["speed"] != "very_slow"]
        out[m] = {
            "G2": round(float(np.mean(oks + oke)), 4),
            "start_acc": round(float(np.mean(oks)), 4),
            "end_acc": round(float(np.mean(oke)), 4),
            "G2_excl_mujawwad": round(float(np.mean(
                [w["ok_start"] for w in nonmj] + [w["ok_end"] for w in nonmj])), 4),
            "median_signed_start_ms": round(float(np.median(es)), 1),
            "median_signed_end_ms": round(float(np.median(ee)), 1),
            "pct_start_late": round(float((es > 0).mean()) * 100, 1),
            "pct_end_late": round(float((ee > 0).mean()) * 100, 1),
            "abs_err_ms": {"median": round(float(np.percentile(allerr, 50)), 1),
                           "p90": round(float(np.percentile(allerr, 90)), 1),
                           "p99": round(float(np.percentile(allerr, 99)), 1)},
            "by_speed": by_speed,
            "gap_to_spec": round(0.85 - float(np.mean(oks + oke)), 4),
            "pass_spec": bool(np.mean(oks + oke) >= 0.85),
        }
    return out


def main():
    report = {"experiment": "3_decode_extent",
              "spec_threshold": g2.G2_THRESHOLD,
              "tolerance_ms": g2.G2_TOLERANCE_MS,
              "harness": "05_g2_alignment.py imported UNMODIFIED",
              "corpus": "RATL-Bench-v0 unchanged",
              "training": "NONE - decode-side only",
              "reference_measured": REF,
              "selection_caveat": ("choosing a rule on RATL-Bench-v0 is selection "
                                   "on the eval set; the winner needs held-out "
                                   "confirmation before it is a trusted V1 number"),
              "results": {}}
    for tag, path in CKPTS.items():
        if not path.exists():
            print(f"[skip] {tag}: no checkpoint")
            continue
        report["results"][tag] = summarise(evaluate(tag, path))

    (RESULTS / "18_decode_extent.json").write_text(
        json.dumps(report, indent=2, ensure_ascii=False), encoding="utf-8")

    print("\n" + "=" * 78)
    print("EXPERIMENT 3 - DECODE-SIDE WORD EXTENT   (SPEC G2 >= 0.85, unchanged)")
    print("=" * 78)
    for tag in report["results"]:
        print(f"\n--- {tag} ---")
        print(f"{'method':<16}{'G2':>8}{'start':>8}{'end':>8}{'noMujaw':>9}"
              f"{'sgn_st':>8}{'sgn_en':>8}{'gap':>8}")
        for m in METHODS:
            r = report["results"][tag].get(m)
            if not r:
                continue
            print(f"{m:<16}{r['G2']:>8.4f}{r['start_acc']:>8.4f}{r['end_acc']:>8.4f}"
                  f"{r['G2_excl_mujawwad']:>9.4f}"
                  f"{r['median_signed_start_ms']:>+8.1f}{r['median_signed_end_ms']:>+8.1f}"
                  f"{r['gap_to_spec']:>8.4f}")
    print("\nreference  exp1 G2 0.6776 | exp1b G2 0.6707 | wav2vec2 G2 0.5611")


if __name__ == "__main__":
    main()
