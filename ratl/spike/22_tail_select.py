"""
RATL M1b - Mujawwad tail repair. Selection on RATL-Val-v0 ONLY.

No training. No downloads. RATL-Bench-v0 untouched by this script.

DIAGNOSIS (M1_RESULTS.md section 5): Mujawwad G2 0.7082 is the entire remaining
deficit. Its median errors are fine (+40 ms start, +10 ms end) but end |err| p90
is 1657 ms -- a small number of catastrophic clips. The worst are 36:1, 93:1,
103:1, all 0.00, all SHORT ayat with extreme elongation.

Mechanism: in a 1-2 word ayah, every word is an EDGE word, so M2E_edge_spike
falls back to the CTC spike on the outer side. A madd held for many seconds is
emitted as one narrow spike surrounded by blank, so the spike span is a fraction
of the true acoustic extent and the outer boundary is off by seconds.

APPROACH A - speech-extent edges ("M2S"). The outer edge of an edge word is not
frame 0, and not the spike: it is where SPEECH begins or ends. Compute a
frame-level speech mask from waveform RMS and extend the outer boundary to the
edge of the speech run containing the spike. Interior boundaries keep the M2E
separator logic, which already measures 0.8499.
  - TOP_DB (speech/silence threshold) is swept on held-out data, not assumed.

APPROACH B - alignment confidence -> UNCERTAIN (SPEC section 4). Per-word mean
Viterbi path log-probability. Reported as an abstention RATE and as accuracy on
the retained subset.
  *** G2 IS ALWAYS COMPUTED OVER ALL WORDS. *** Abstaining on hard words and
  scoring only the rest would be reinterpreting the 0.85 requirement, which is
  forbidden. Confidence is a product feature here, never a gate adjustment.

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

CORPUS = HERE / "corpus"
RESULTS = HERE / "results"
CKPT = HERE / "models" / "exp1" / "best.pt"

TOP_DBS = [25.0, 30.0, 35.0, 40.0]
TOL = 150.0
HOP = int(m1.SR / m1.FPS)          # 320 samples = 20 ms, one encoder frame


def speech_runs(audio, n_frames, top_db):
    """Contiguous speech runs as frame indices, from waveform RMS."""
    n = n_frames * HOP
    a = np.pad(audio, (0, max(0, n - len(audio))))[:n].reshape(n_frames, HOP)
    rms = np.sqrt((a.astype(np.float64) ** 2).mean(axis=1) + 1e-12)
    db = 20.0 * np.log10(rms / (rms.max() + 1e-12) + 1e-12)
    mask = db > -top_db
    runs, start = [], None
    for i, v in enumerate(mask):
        if v and start is None:
            start = i
        elif not v and start is not None:
            runs.append((start, i))
            start = None
    if start is not None:
        runs.append((start, len(mask)))
    return runs


def run_containing(runs, t):
    for s, e in runs:
        if s <= t < e:
            return (s, e)
    return None


def bounds_m2s(spans, tok, wi, n_tok, T, runs):
    """M2E interior logic; outer edges extended to the speech-run boundary."""
    sp = spans[wi]
    if sp is None:
        return None
    lo, hi = sp
    a_lo, a_hi = tok[lo], tok[hi - 1]
    if a_lo is None or a_hi is None:
        return None
    raw_s, raw_e = float(a_lo[0]), float(a_hi[1] + 1)

    if lo == 0:
        r = run_containing(runs, int(raw_s)) or run_containing(runs, max(0, int(raw_s) - 1))
        st = float(r[0]) if r else raw_s
    else:
        st = float(tok[lo - 1][1] + 1) if tok[lo - 1] is not None else raw_s

    if hi >= n_tok:
        r = run_containing(runs, min(T - 1, int(raw_e) - 1))
        en = float(r[1]) if r else raw_e
    else:
        en = float(tok[hi][0]) if tok[hi] is not None else raw_e
    return st, en


def word_conf(logp, tok, spans, wi):
    """Mean path log-prob over the frames the Viterbi assigned to this word."""
    sp = spans[wi]
    if sp is None:
        return None
    lo, hi = sp
    vals = []
    for ti in range(lo, hi):
        t = tok[ti]
        if t is None:
            continue
        for f in range(t[0], t[1] + 1):
            if 0 <= f < logp.shape[0]:
                vals.append(logp[f].max())
    return float(np.mean(vals)) if vals else None


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

    val = json.loads((CORPUS / "ratl-val-v0.json").read_text(encoding="utf-8"))
    items = val["items"]
    print(f"[val] {len(items)} held-out items")

    rows = {("M2E", None): []}
    for td in TOP_DBS:
        rows[("M2S", td)] = []
    t0 = time.time()

    for n, it in enumerate(items, 1):
        if not it["segments"]:
            continue
        audio = np.concatenate([librosa.load(str(tr.AUDIO / p), sr=m1.SR, mono=True)[0]
                                for p in it["parts"]])
        ids, spans, _ = e1.build_tokens_v2([{"tr": w} for w in it["words"]], stoi)
        if not ids:
            continue
        logits = m1.logits_overlap(audio, model, fe, dev)
        T = logits.shape[0]
        logp = g2.log_softmax(logits.astype(np.float64))
        tok = g2.ctc_forced_align(logp, ids, blank=0)
        gt_segs = {s[0]: s for s in it["segments"]}
        runs_by_td = {td: speech_runs(audio, T, td) for td in TOP_DBS}
        is_mj = "Mujawwad" in it["reciter"]

        for wi in range(len(spans)):
            seg = gt_segs.get(wi)
            if seg is None:
                continue
            cf = word_conf(logp, tok, spans, wi)
            for key in rows:
                rule, td = key
                b = (d3.word_bounds("M2E_edge_spike", spans, tok, wi, len(ids), T)
                     if rule == "M2E" else
                     bounds_m2s(spans, tok, wi, len(ids), T, runs_by_td[td]))
                if b is None:
                    continue
                rows[key].append((b[0] * m1.MS_PER_FRAME - seg[2],
                                  b[1] * m1.MS_PER_FRAME - seg[3],
                                  is_mj, cf, it["kind"]))
        if n % 150 == 0:
            print(f"      {n}/{len(items)}  ({time.time()-t0:.0f}s)", flush=True)

    def score(rs, pred=None):
        sel = [r for r in rs if pred is None or pred(r)]
        if not sel:
            return None
        es = np.array([r[0] for r in sel])
        ee = np.array([r[1] for r in sel])
        ok = np.concatenate([np.abs(es) <= TOL, np.abs(ee) <= TOL])
        return {"n": len(sel), "G2": round(float(ok.mean()), 4),
                "start": round(float((np.abs(es) <= TOL).mean()), 4),
                "end": round(float((np.abs(ee) <= TOL).mean()), 4)}

    print("\n" + "=" * 78)
    print("M1b SELECTION on RATL-Val-v0 (held-out). Bench NOT used.")
    print("=" * 78)
    print(f"{'rule':<10}{'top_db':>8}{'G2':>9}{'start':>8}{'end':>8}"
          f"{'Mujawwad':>10}{'other':>9}")
    table, best = [], None
    for key, rs in rows.items():
        rule, td = key
        s = score(rs)
        mj = score(rs, lambda r: r[2])
        ot = score(rs, lambda r: not r[2])
        rec = {"rule": rule, "top_db": td, **s,
               "mujawwad": mj["G2"] if mj else None,
               "other": ot["G2"] if ot else None}
        table.append(rec)
        print(f"{rule:<10}{str(td):>8}{s['G2']:>9.4f}{s['start']:>8.4f}"
              f"{s['end']:>8.4f}{rec['mujawwad']:>10.4f}{rec['other']:>9.4f}")
        if best is None or s["G2"] > best["G2"]:
            best = rec

    # ---- Approach B: confidence -> UNCERTAIN, reported only ----
    bk = (best["rule"], best["top_db"])
    rs = [r for r in rows[bk] if r[3] is not None]
    confs = np.array([r[3] for r in rs])
    print(f"\nApproach B - confidence abstention (rule {bk[0]} top_db {bk[1]})")
    print("  NOTE: G2 over ALL words is the gate. Abstention never adjusts it.")
    conf_tbl = []
    for pct in (0, 2, 5, 10):
        thr = float(np.percentile(confs, pct)) if pct else -1e9
        keep = [r for r in rs if r[3] > thr]
        s = score(keep)
        mj = score([r for r in keep if r[2]])
        conf_tbl.append({"abstain_pct": pct, "threshold": round(thr, 4),
                         "G2_retained": s["G2"],
                         "mujawwad_retained": mj["G2"] if mj else None})
        print(f"  abstain {pct:>2}%  ->  retained G2 {s['G2']:.4f}   "
              f"retained Mujawwad {mj['G2'] if mj else float('nan'):.4f}")

    (RESULTS / "22_tail_select.json").write_text(json.dumps(
        {"stage": "M1b_selection", "selection_set": "RATL-Val-v0 (held-out)",
         "bench_untouched": True, "table": table, "best_on_val": best,
         "confidence_table": conf_tbl,
         "gate_rule": "G2 is computed over ALL words; abstention is reported only"},
        indent=2, ensure_ascii=False), encoding="utf-8")
    print(f"\nBEST ON VAL: {best['rule']} top_db={best['top_db']} "
          f"-> val G2 {best['G2']:.4f} (Mujawwad {best['mujawwad']:.4f})")


if __name__ == "__main__":
    main()
