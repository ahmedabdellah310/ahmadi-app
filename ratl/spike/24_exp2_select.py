"""
RATL Experiment 2 - decode selection on RATL-Val-v0. HELD-OUT ONLY.

The boundary head emits, per 20 ms frame, P(word starts here) and P(word ends
here). Placing boundaries greedily from those posteriors would ignore the known
word ORDER, so instead all 2N boundaries are placed jointly by dynamic
programming under a strict monotonicity constraint:

    s_0 < e_0 < s_1 < e_1 < ... < s_{N-1} < e_{N-1}

    f[k][t] = score_k(t) + max_{t' < t} f[k-1][t']

score_k is log P(start) on even k and log P(end) on odd k. O(2N*T) with a prefix
maximum. This is the same class of algorithm as the CTC Viterbi already in use,
but scored by DIRECT temporal supervision rather than transcription spikes.

Variants compared on held-out data:
  V_m2e     M2E_edge_spike from the CTC head alone  (M1 incumbent, 0.8787 val)
  V_dp      pure boundary-head DP
  V_mean    midpoint of V_m2e and V_dp
  V_win_K   boundary DP constrained to within K frames of the M2E estimate,
            so the CTC alignment supplies coarse anchoring and the boundary
            head supplies local precision

RATL-Bench-v0 is NOT touched here. The bench is run once, afterwards, by
25_exp2_gate.py, using whichever variant wins on held-out data.

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

CORPUS = HERE / "corpus"
RESULTS = HERE / "results"
CKPT = HERE / "models" / "exp2" / "best.pt"

WINDOWS = [25, 50, 100]        # frames = 0.5 s, 1 s, 2 s
TOL = 150.0
NEG = -1e18


def run_windows(audio, model, fe, dev):
    """Overlap-and-discard stitching for BOTH heads (same geometry as M1)."""
    win, hop = int(m1.WIN_S * m1.SR), int(m1.HOP_S * m1.SR)
    mfr = int(m1.MARGIN_S * m1.FPS)
    n = len(audio)
    outc, outb = [], []

    def one(ch):
        f = fe(ch, sampling_rate=m1.SR, return_tensors="np",
               padding="max_length", max_length=win)["input_features"][0]
        f = f[:, :tr.MEL_FRAMES]
        if f.shape[1] < tr.MEL_FRAMES:
            f = np.pad(f, ((0, 0), (0, tr.MEL_FRAMES - f.shape[1])),
                       constant_values=f.min())
        x = torch.from_numpy(f[None].astype(np.float32)).to(dev)
        with torch.no_grad():
            lg, bl = model(x)
        return lg[0].float().cpu().numpy(), bl[0].float().cpu().numpy()

    if n <= win:
        c, b = one(audio)
        v = max(1, int(min(n / m1.SR * m1.FPS, tr.ENC_FRAMES)))
        return c[:v], b[:v]
    start = 0
    while start < n:
        ch = audio[start:start + win]
        c, b = one(ch)
        v = max(1, int(min(len(ch) / m1.SR * m1.FPS, tr.ENC_FRAMES)))
        c, b = c[:v], b[:v]
        lo = 0 if start == 0 else mfr
        hi = v if start + win >= n else v - mfr
        if hi > lo:
            outc.append(c[lo:hi])
            outb.append(b[lo:hi])
        if start + win >= n:
            break
        start += hop
    return np.concatenate(outc, axis=0), np.concatenate(outb, axis=0)


def boundary_dp(logps, logpe, n_words, lo=None, hi=None):
    """Jointly place 2N monotonic boundaries. lo/hi are per-boundary frame bounds."""
    T = len(logps)
    K = 2 * n_words
    if T < K + 1:
        return None
    f = np.full((K, T), NEG)
    bk = np.zeros((K, T), dtype=np.int32)
    for k in range(K):
        sc = logps if k % 2 == 0 else logpe
        allowed = np.zeros(T, dtype=bool)
        a = 0 if lo is None else max(0, lo[k])
        b = T if hi is None else min(T, hi[k] + 1)
        if b <= a:
            a, b = 0, T
        allowed[a:b] = True
        if k == 0:
            f[0] = np.where(allowed, sc, NEG)
            continue
        run = np.maximum.accumulate(f[k - 1])
        arg = np.maximum.accumulate(
            np.where(f[k - 1] > np.concatenate([[NEG], run[:-1]]),
                     np.arange(T), 0))
        prev = np.concatenate([[NEG], run[:-1]])
        pidx = np.concatenate([[0], arg[:-1]])
        f[k] = np.where(allowed & (prev > NEG / 2), sc + prev, NEG)
        bk[k] = pidx
    t = int(np.argmax(f[K - 1]))
    if f[K - 1, t] <= NEG / 2:
        return None
    out = [0] * K
    for k in range(K - 1, -1, -1):
        out[k] = t
        t = int(bk[k, t])
    return [(out[2 * i], out[2 * i + 1] + 1) for i in range(n_words)]


def main():
    import librosa

    dev = "cuda" if torch.cuda.is_available() else "cpu"
    ck = torch.load(CKPT, map_location=dev, weights_only=False)
    itos = ck["vocab"]
    stoi = {c: i for i, c in enumerate(itos)}
    model = x2.EncCTCBoundary(len(itos)).to(dev)
    model.load_state_dict(ck["model"])
    model.eval()
    print(f"[model] epoch {ck['epoch']}  val {ck['val']:.4f} "
          f"(ctc {ck['val_ctc']:.4f} bnd {ck['val_bnd']:.4f})")

    from transformers import WhisperFeatureExtractor

    fe = WhisperFeatureExtractor.from_pretrained(tr.BASE)
    fe.chunk_length = int(m1.WIN_S)
    fe.n_samples = int(m1.WIN_S * m1.SR)
    fe.nb_max_frames = tr.MEL_FRAMES

    val = json.loads((CORPUS / "ratl-val-v0.json").read_text(encoding="utf-8"))
    items = val["items"]
    print(f"[val] {len(items)} held-out items")

    variants = ["V_m2e", "V_dp", "V_mean"] + [f"V_win_{k}" for k in WINDOWS]
    rows = {v: [] for v in variants}
    t0 = time.time()

    for n, it in enumerate(items, 1):
        if not it["segments"]:
            continue
        audio = np.concatenate([librosa.load(str(tr.AUDIO / p), sr=m1.SR, mono=True)[0]
                                for p in it["parts"]])
        ids, spans, _ = e1.build_tokens_v2([{"tr": w} for w in it["words"]], stoi)
        if not ids:
            continue
        clog, blog = run_windows(audio, model, fe, dev)
        T = clog.shape[0]
        logp = g2.log_softmax(clog.astype(np.float64))
        tok = g2.ctc_forced_align(logp, ids, blank=0)

        bl = blog.astype(np.float64)
        logps = -np.logaddexp(0.0, -bl[:, 0])
        logpe = -np.logaddexp(0.0, -bl[:, 1])

        nw = len(spans)
        m2e = [d3.word_bounds("M2E_edge_spike", spans, tok, wi, len(ids), T)
               for wi in range(nw)]
        if any(b is None for b in m2e):
            m2e = [b if b else (0.0, float(T)) for b in m2e]

        dp = boundary_dp(logps, logpe, nw)
        wins = {}
        for k in WINDOWS:
            lo = [int(m2e[i // 2][i % 2]) - k for i in range(2 * nw)]
            hi = [int(m2e[i // 2][i % 2]) + k for i in range(2 * nw)]
            wins[k] = boundary_dp(logps, logpe, nw, lo, hi)

        gt_segs = {s[0]: s for s in it["segments"]}
        is_mj = "Mujawwad" in it["reciter"]
        for wi in range(nw):
            seg = gt_segs.get(wi)
            if seg is None:
                continue
            wdur = seg[3] - seg[2]
            cand = {"V_m2e": m2e[wi],
                    "V_dp": dp[wi] if dp else None,
                    "V_mean": (tuple((np.array(m2e[wi]) + np.array(dp[wi])) / 2)
                               if dp else None)}
            for k in WINDOWS:
                cand[f"V_win_{k}"] = wins[k][wi] if wins[k] else None
            for v, b in cand.items():
                if b is None:
                    continue
                rows[v].append((b[0] * m1.MS_PER_FRAME - seg[2],
                                b[1] * m1.MS_PER_FRAME - seg[3], is_mj, wdur))
        if n % 150 == 0:
            print(f"      {n}/{len(items)}  ({time.time()-t0:.0f}s)", flush=True)

    def score(rs, pred=None):
        sel = [r for r in rs if pred is None or pred(r)]
        if not sel:
            return None
        es = np.array([r[0] for r in sel])
        ee = np.array([r[1] for r in sel])
        return {"n": len(sel),
                "G2": round(float(np.mean(np.concatenate(
                    [np.abs(es) <= TOL, np.abs(ee) <= TOL]))), 4),
                "start": round(float((np.abs(es) <= TOL).mean()), 4),
                "end": round(float((np.abs(ee) <= TOL).mean()), 4)}

    print("\n" + "=" * 92)
    print("EXPERIMENT 2 SELECTION on RATL-Val-v0 (held-out). Bench NOT used.")
    print("=" * 92)
    print(f"{'variant':<12}{'G2':>9}{'start':>8}{'end':>8}{'Mujawwad':>10}"
          f"{'other':>8}{'<1s':>8}{'1-2s':>8}{'2-4s':>8}{'>4s':>8}")
    table, best = [], None
    for v in variants:
        rs = rows[v]
        s = score(rs)
        if not s:
            continue
        rec = {"variant": v, **s,
               "mujawwad": (score(rs, lambda r: r[2]) or {}).get("G2"),
               "other": (score(rs, lambda r: not r[2]) or {}).get("G2"),
               "dur_lt1000": (score(rs, lambda r: r[3] < 1000) or {}).get("G2"),
               "dur_1_2k": (score(rs, lambda r: 1000 <= r[3] < 2000) or {}).get("G2"),
               "dur_2_4k": (score(rs, lambda r: 2000 <= r[3] < 4000) or {}).get("G2"),
               "dur_gt4k": (score(rs, lambda r: r[3] >= 4000) or {}).get("G2")}
        table.append(rec)
        f = lambda x: f"{x:>8.4f}" if x is not None else "       -"  # noqa: E731
        print(f"{v:<12}{s['G2']:>9.4f}{s['start']:>8.4f}{s['end']:>8.4f}"
              f"{rec['mujawwad']:>10.4f}{f(rec['other'])}{f(rec['dur_lt1000'])}"
              f"{f(rec['dur_1_2k'])}{f(rec['dur_2_4k'])}{f(rec['dur_gt4k'])}")
        if best is None or s["G2"] > best["G2"]:
            best = rec

    (RESULTS / "24_exp2_select.json").write_text(json.dumps(
        {"stage": "exp2_selection", "selection_set": "RATL-Val-v0 (held-out)",
         "bench_untouched": True, "m1_incumbent_val_G2": 0.8787,
         "table": table, "best_on_val": best}, indent=2), encoding="utf-8")
    print(f"\nBEST ON VAL: {best['variant']} -> {best['G2']:.4f} "
          f"(M1 incumbent 0.8787, Mujawwad {best['mujawwad']:.4f})")


if __name__ == "__main__":
    main()
