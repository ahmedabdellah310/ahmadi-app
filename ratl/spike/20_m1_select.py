"""
RATL M1 - decode configuration selection on RATL-Val-v0. HELD-OUT ONLY.

RATL-Bench-v0 is NOT touched by this script. Every parameter below is chosen on
held-out data; the bench is run once, afterwards, by 21_m1_gate.py.

Three factors, swept jointly:

  WINDOWING
    nonoverlap  current: 15 s non-overlapping windows, logits concatenated.
                A word straddling a cut is decoded with no context on one side.
    overlap     15 s windows on a 10 s hop; the 2.5 s margins on each side are
                CONTEXT ONLY and discarded, so every retained frame was decoded
                with real audio around it. Frame rate stays 50 fps.

  BOUNDARY RULE   M2_separator / M3_full_gap  (M0, M1 already dominated)

  LATENCY OFFSET  constant ms subtracted from predicted times. Exp 3 left a
                  uniform late bias (+34.6 start, +26.3 end) consistent with the
                  encoder conv receptive-field centre. Swept, not assumed.

$0, no downloads, no training. THROWAWAY SPIKE CODE. Not RATL Core.
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


g2 = _load("g2", "05_g2_alignment.py")     # FROZEN: aligner + log_softmax only
tr = _load("tr", "10_train_exp1.py")
e1 = _load("e1", "13_eval_exp1.py")
d3 = _load("d3", "18_decode_extent.py")    # word_bounds

CORPUS = HERE / "corpus"
RESULTS = HERE / "results"
CKPT = HERE / "models" / "exp1" / "best.pt"      # best config from Exp 3

SR = 16000
FPS = 50.0
MS_PER_FRAME = 1000.0 / FPS
WIN_S = 15.0
HOP_S = 10.0
MARGIN_S = (WIN_S - HOP_S) / 2.0

RULES = ["M2_separator", "M2E_edge_spike", "M3_full_gap"]
OFFSETS = [0.0, 10.0, 20.0, 30.0, 40.0]
WINDOWINGS = ["nonoverlap", "overlap"]
TOL = 150.0


def logits_nonoverlap(audio, model, fe, dev):
    """Current method: contiguous 15 s windows, no shared context."""
    win = int(WIN_S * SR)
    parts = []
    for i in range(0, max(1, len(audio)), win):
        ch = audio[i:i + win]
        parts.append(_run(ch, model, fe, dev)[:max(1, int(min(len(ch) / SR * FPS,
                                                              tr.ENC_FRAMES)))])
    return np.concatenate(parts, axis=0)


def logits_overlap(audio, model, fe, dev):
    """15 s windows on a 10 s hop; discard the 2.5 s context margins."""
    win = int(WIN_S * SR)
    hop = int(HOP_S * SR)
    mfr = int(MARGIN_S * FPS)
    n = len(audio)
    if n <= win:
        ch = audio
        lg = _run(ch, model, fe, dev)
        return lg[:max(1, int(min(n / SR * FPS, tr.ENC_FRAMES)))]
    out = []
    start = 0
    while start < n:
        ch = audio[start:start + win]
        lg = _run(ch, model, fe, dev)
        valid = max(1, int(min(len(ch) / SR * FPS, tr.ENC_FRAMES)))
        lg = lg[:valid]
        lo = 0 if start == 0 else mfr
        hi = valid if start + win >= n else valid - mfr
        if hi > lo:
            out.append(lg[lo:hi])
        if start + win >= n:
            break
        start += hop
    return np.concatenate(out, axis=0)


def _run(ch, model, fe, dev):
    win = int(WIN_S * SR)
    f = fe(ch, sampling_rate=SR, return_tensors="np",
           padding="max_length", max_length=win)["input_features"][0]
    f = f[:, :tr.MEL_FRAMES]
    if f.shape[1] < tr.MEL_FRAMES:
        f = np.pad(f, ((0, 0), (0, tr.MEL_FRAMES - f.shape[1])),
                   constant_values=f.min())
    x = torch.from_numpy(f[None].astype(np.float32)).to(dev)
    with torch.no_grad():
        return model(x)[0].float().cpu().numpy()


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
    fe.chunk_length = int(WIN_S)
    fe.n_samples = int(WIN_S * SR)
    fe.nb_max_frames = tr.MEL_FRAMES

    val = json.loads((CORPUS / "ratl-val-v0.json").read_text(encoding="utf-8"))
    items = val["items"]
    print(f"[val] {len(items)} items ({val['n_short']} short, {val['n_long']} long)")

    # rows[(windowing, rule)] = list of (err_start, err_end, kind, speed)
    rows = {(w, r): [] for w in WINDOWINGS for r in RULES}
    t0 = time.time()

    for n, it in enumerate(items, 1):
        segs = it["segments"]
        if not segs:
            continue
        audio = np.concatenate([librosa.load(str(tr.AUDIO / p), sr=SR, mono=True)[0]
                                for p in it["parts"]])
        words = [{"tr": w} for w in it["words"]]
        ids, spans, _ = e1.build_tokens_v2(words, stoi)
        if not ids:
            continue
        gt_segs = {s[0]: s for s in segs}

        for wmode in WINDOWINGS:
            logits = (logits_overlap if wmode == "overlap" else logits_nonoverlap)(
                audio, model, fe, dev)
            T = logits.shape[0]
            logp = g2.log_softmax(logits.astype(np.float64))
            tok = g2.ctc_forced_align(logp, ids, blank=0)
            for rule in RULES:
                for wi in range(len(spans)):
                    seg = gt_segs.get(wi)
                    if seg is None:
                        continue
                    b = d3.word_bounds(rule, spans, tok, wi, len(ids), T)
                    if b is None:
                        continue
                    rows[(wmode, rule)].append((
                        b[0] * MS_PER_FRAME - seg[2],
                        b[1] * MS_PER_FRAME - seg[3],
                        it["kind"], it["speed"]))
        if n % 100 == 0:
            print(f"      {n}/{len(items)}  ({time.time()-t0:.0f}s)", flush=True)

    # ---------- score every (windowing, rule, offset) on held-out ----------
    def score(rs, off, pred=None):
        sel = [r for r in rs if pred is None or pred(r)]
        if not sel:
            return None
        es = np.array([r[0] for r in sel]) - off
        ee = np.array([r[1] for r in sel]) - off
        oks = np.abs(es) <= TOL
        oke = np.abs(ee) <= TOL
        return {"n": len(sel),
                "G2": round(float(np.mean(np.concatenate([oks, oke]))), 4),
                "start": round(float(oks.mean()), 4),
                "end": round(float(oke.mean()), 4),
                "sgn_start": round(float(np.median(es)), 1),
                "sgn_end": round(float(np.median(ee)), 1)}

    table, best = [], None
    for w in WINDOWINGS:
        for r in RULES:
            for off in OFFSETS:
                s = score(rows[(w, r)], off)
                if not s:
                    continue
                rec = {"windowing": w, "rule": r, "offset_ms": off, **s,
                       "long": score(rows[(w, r)], off, lambda x: x[2] == "long"),
                       "short": score(rows[(w, r)], off, lambda x: x[2] == "short")}
                table.append(rec)
                if best is None or rec["G2"] > best["G2"]:
                    best = rec

    report = {"stage": "M1_selection", "selection_set": "RATL-Val-v0 (held-out)",
              "bench_untouched": True, "checkpoint": "exp1",
              "tolerance_ms": TOL, "spec_threshold": 0.85,
              "n_items": len(items), "table": table, "best_on_val": best}
    (RESULTS / "20_m1_select.json").write_text(
        json.dumps(report, indent=2, ensure_ascii=False), encoding="utf-8")

    print("\n" + "=" * 86)
    print("M1 SELECTION on RATL-Val-v0 (held-out). Bench NOT used.")
    print("=" * 86)
    print(f"{'window':<12}{'rule':<15}{'off':>5}{'G2':>9}{'start':>8}{'end':>8}"
          f"{'G2long':>9}{'G2short':>9}")
    for rec in sorted(table, key=lambda x: -x["G2"])[:14]:
        print(f"{rec['windowing']:<12}{rec['rule']:<15}{rec['offset_ms']:>5.0f}"
              f"{rec['G2']:>9.4f}{rec['start']:>8.4f}{rec['end']:>8.4f}"
              f"{rec['long']['G2']:>9.4f}{rec['short']['G2']:>9.4f}")
    print(f"\nBEST ON VAL: {best['windowing']} + {best['rule']} + "
          f"{best['offset_ms']:.0f} ms  ->  val G2 {best['G2']:.4f}")

    # isolate the windowing effect at the best rule/offset
    for r in RULES:
        a = [x for x in table if x["windowing"] == "nonoverlap"
             and x["rule"] == r and x["offset_ms"] == best["offset_ms"]][0]
        b = [x for x in table if x["windowing"] == "overlap"
             and x["rule"] == r and x["offset_ms"] == best["offset_ms"]][0]
        print(f"  windowing effect [{r}] long items: "
              f"{a['long']['G2']:.4f} -> {b['long']['G2']:.4f} "
              f"({b['long']['G2']-a['long']['G2']:+.4f})")


if __name__ == "__main__":
    main()
