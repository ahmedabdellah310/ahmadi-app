"""M2.0 - G3 / G4 evaluation of the reference lattice.

Methodology, in the order it is executed and with no exceptions:

  1. Build RATL-WordErr-v1 and RATL-Clean-v1 from cached audio, seeded.
  2. Score every item ONCE, caching the raw per-word signals (LLR, garbage runs,
     substitution score) across a small grid of the two lattice-shape parameters
     that actually change the Viterbi path.
  3. SELECT thresholds on the SEEN-RECITER tier only.
  4. Run the RECITER-DISJOINT tier ONCE with the selected configuration. That is
     the held-out gate. It is never tuned on, never re-run for a better number.

RATL-Bench-v0 is not used here at all - it is the G2 gate and is not a tuning
signal for anything.

The null-mutation control carries the same 10 ms splice seam as the real
mutations but changes no content, so it bounds how much of any detection is the
seam rather than the error.
"""
import importlib.util
import itertools
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
m1 = _load("m1", "20_m1_select.py")
v2 = _load("v2", "verify_onnx_g2_e2e.py", ROOT / "models")
lat = _load("lat", "lattice.py", ROOT / "eval")
mut = _load("mut", "corpus/mutate.py", ROOT / "eval")

OUT = ROOT / "models" / "out"
MODEL = "ratl-align-v1-e2e-fp32.onnx"
REPORT = ROOT / "eval" / "g3g4_eval.json"
SEED = 1450
PER_OP_SEEN, PER_OP_DISJ = 40, 22
N_CLEAN_SEEN, N_CLEAN_DISJ = 60, 40

# the two parameters that change the Viterbi PATH; everything else is a
# post-hoc threshold on recorded signals
GRID_SHAPE = [{"garb_pen": gp, "lam_ins": li}
              for gp in (2.0, 4.0, 7.0) for li in (-20.0, -60.0)]
# Ranged from the measured seen-tier distributions, not guessed:
#   llr_total  clean p5 +39.5 / deleted p95 -36.0  -> threshold belongs in the gap
#   sub        clean 0.0 at every percentile / substituted p5 0.414
GRID_SKIP = [-40.0, -30.0, -20.0, -10.0, 0.0, 10.0, 20.0, 30.0]
GRID_SUB = [0.05, 0.10, 0.20, 0.30, 0.40]
GRID_UNC = [-np.inf, 0.0, 1.0, 2.0, 4.0]
LAM_REP = -25.0


def load_sess():
    import onnxruntime as ort
    so = ort.SessionOptions()
    so.intra_op_num_threads = 1
    return ort.InferenceSession(str(OUT / MODEL), so, providers=["CPUExecutionProvider"])


def score_items(items, stoi, sess, tag):
    """One ONNX pass + one LLR pass + |GRID_SHAPE| lattice passes per item."""
    scored, t0 = [], time.time()
    for k, it in enumerate(items):
        try:
            audio, segs, wi_local = mut.render(it)
        except Exception:  # noqa: BLE001
            continue
        if audio is None or len(audio) < mut.SR // 2:
            continue
        words = mut.translit_offline(it["surah"], it["ayah"])
        if not words:
            continue
        logp = g2.log_softmax(v2.run_windows(audio, sess).astype(np.float64))
        llrs = lat.llr_all(logp, words, stoi)
        per_shape = {}
        for gi, sh in enumerate(GRID_SHAPE):
            cfg = lat.LatticeConfig(lam_skip=0.0, lam_rep=LAM_REP,
                                    tau_sub=np.inf, tau_uncertain=-np.inf, **sh)
            per_shape[gi] = raw_signals(logp, words, stoi, cfg, llrs)
        scored.append({"item": it, "n_words": len(words), "llrs": llrs,
                       "shapes": per_shape})
        if (k + 1) % 40 == 0:
            print(f"      [{tag}] {k+1}/{len(items)}  ({time.time()-t0:.0f}s)", flush=True)
    return scored


def raw_signals(logp, words, stoi, cfg, llrs):
    """Per-word signals under one lattice SHAPE, before any threshold is applied."""
    ids, wspans, _ = e1.build_tokens_v2(words, stoi)
    T = logp.shape[0]
    path, _, tb = lat.viterbi(logp, ids, wspans, cfg)
    n_ctc = tb["n_ctc"]
    lab = lat.frozen_spans(path, ids, n_ctc)
    gset = set(tb["gap_state"].values())
    inv = {v: k for k, v in tb["gap_state"].items()}
    isg = np.array([int(s) in gset for s in path])
    garb = {}
    for a, b in lat._runs(isg):
        if b - a >= cfg.min_ins_frames:
            garb.setdefault(inv[int(path[a])], []).append([int(a), int(b)])
    reps = lat.arc_traversals(path, tb["rep_edges"])
    out = {"garbage": garb, "terminal": int(path[-1]), "T": int(T), "words": {}}
    for wi, sp in enumerate(wspans):
        if sp is None:
            continue
        lo, hi = sp
        wmask = np.zeros(T, dtype=bool)
        for l in range(lo, hi):
            wmask |= (path == 2 * l + 1)
        runs = lat._runs(wmask)
        sub = None
        if runs:
            a, b = runs[0][0], runs[-1][1]
            seg = path[a:b]
            labs = np.array([tb["ext"][s] if s < n_ctc else 0 for s in seg])
            sub = float(np.mean(logp[np.arange(a, b)].max(axis=1))
                        - np.mean(logp[np.arange(a, b), labs]))
        out["words"][wi] = {
            "present": bool(runs), "n_runs": len(runs), "sub": sub,
            "n_rep": int(reps.get(wi, 0)),
            "llr": llrs.get(wi, {}).get("per_token"),
            "llr_total": llrs.get(wi, {}).get("total"),
            "n_tok": hi - lo,
            "tail": bool(runs and runs[-1][1] >= T),
            "partial": bool(runs) and sum(1 for l in range(lo, hi)
                                          if lab[l] is not None) < (hi - lo),
        }
    return out


def decide(sig, wi, lam_skip, tau_sub, tau_unc):
    """Apply thresholds to recorded signals. Mirrors lattice.assess exactly."""
    w = sig["words"].get(wi)
    if w is None:
        return lat.UNCERTAIN
    if w["llr_total"] is not None and w["llr_total"] < lam_skip:
        said = sig["garbage"].get(wi) or sig["garbage"].get(wi + 1)
        return lat.SUBSTITUTION if said else lat.OMISSION
    if w.get("n_rep", 0) >= 1:
        return lat.REPETITION
    if w["tail"] and w["partial"]:
        return lat.INCOMPLETE
    if w["sub"] is not None and w["sub"] > tau_sub:
        return lat.SUBSTITUTION
    if w["llr"] is not None and w["llr"] < tau_unc:
        return lat.UNCERTAIN
    return lat.CORRECT


def evaluate(scored, gi, lam_skip, tau_sub, tau_unc):
    """Return per-item target-word verdicts plus every clean-word verdict."""
    tgt, clean_words = [], []
    for s in scored:
        it, sig = s["item"], s["shapes"][gi]
        wi = it["word_index"]
        exp = it["expected_status"]
        st = decide(sig, wi, lam_skip, tau_sub, tau_unc)
        # INSERTION is a gap event, not a word status: credit the gap after wi
        if exp == "INSERTION":
            got_ins = bool(sig["garbage"].get(wi + 1) or sig["garbage"].get(wi))
            st_eff = lat.INSERTION if got_ins else st
        else:
            st_eff = st
        tgt.append({"op": it["op"], "expected": exp, "got": st_eff,
                    "reciter": it["reciter"], "clip": it["clip_key"],
                    "ayah": f"{it['surah']}:{it['ayah']}",
                    "wdur": it["word_dur_ms"]})
        if it["op"] in ("clean", "null"):
            for w in range(s["n_words"]):
                clean_words.append({"got": decide(sig, w, lam_skip, tau_sub, tau_unc),
                                    "op": it["op"], "reciter": it["reciter"],
                                    "clip": it["clip_key"]})
    return tgt, clean_words


ERRSET = {lat.OMISSION, lat.SUBSTITUTION, lat.REPETITION, lat.INSERTION, lat.INCOMPLETE}


def metrics(tgt, clean_words):
    g3_items = [r for r in tgt if r["op"] == "delete"]
    g3 = (sum(1 for r in g3_items if r["got"] == lat.OMISSION) / len(g3_items)
          if g3_items else None)
    frr = (sum(1 for r in clean_words if r["got"] in ERRSET) / len(clean_words)
           if clean_words else None)
    unc = (sum(1 for r in clean_words if r["got"] == lat.UNCERTAIN) / len(clean_words)
           if clean_words else None)
    non_correct = (sum(1 for r in clean_words if r["got"] != lat.CORRECT)
                   / len(clean_words) if clean_words else None)
    per = {}
    for cls in sorted(ERRSET):
        tp = sum(1 for r in tgt if r["expected"] == cls and r["got"] == cls)
        fn = sum(1 for r in tgt if r["expected"] == cls and r["got"] != cls)
        fp = (sum(1 for r in tgt if r["expected"] != cls and r["got"] == cls)
              + sum(1 for r in clean_words if r["got"] == cls))
        p = tp / (tp + fp) if tp + fp else None
        rc = tp / (tp + fn) if tp + fn else None
        f1 = (2 * p * rc / (p + rc)) if (p and rc) else None
        per[cls] = {"tp": tp, "fp": fp, "fn": fn,
                    "precision": None if p is None else round(p, 4),
                    "recall": None if rc is None else round(rc, 4),
                    "f1": None if f1 is None else round(f1, 4)}
    return {"G3_omission_recall": None if g3 is None else round(g3, 4),
            "G3_n": len(g3_items),
            "G4_frr": None if frr is None else round(frr, 4),
            "G4_n_clean_words": len(clean_words),
            "uncertain_rate_on_clean": None if unc is None else round(unc, 4),
            "non_correct_rate_on_clean": None if non_correct is None else round(non_correct, 4),
            "per_class": per}


def signal_distributions(scored):
    """Measured spread of the raw signals, so grids are ranged from data.

    The first pass guessed tau_sub's scale and every configuration came out
    infeasible; the grid is now derived from the clean-word distribution rather
    than assumed.
    """
    sub_clean, sub_sub, llr_clean, llr_del = [], [], [], []
    for s in scored:
        it, sig = s["item"], s["shapes"][0]
        wi = it["word_index"]
        w = sig["words"].get(wi)
        if w is None:
            continue
        if it["op"] in ("clean", "null"):
            for k, ww in sig["words"].items():
                if ww["sub"] is not None:
                    sub_clean.append(ww["sub"])
                if ww["llr_total"] is not None:
                    llr_clean.append(ww["llr_total"])
        elif it["op"] == "substitute" and w["sub"] is not None:
            sub_sub.append(w["sub"])
        elif it["op"] == "delete" and w["llr_total"] is not None:
            llr_del.append(w["llr_total"])

    def q(v):
        if not v:
            return None
        a = np.asarray(v, dtype=float)
        return {"n": len(v), **{f"p{p}": round(float(np.percentile(a, p)), 3)
                                for p in (5, 25, 50, 75, 95)}}
    return {"sub_clean": q(sub_clean), "sub_substituted": q(sub_sub),
            "llr_total_clean": q(llr_clean), "llr_total_deleted": q(llr_del)}


def main():
    itos = json.loads((OUT / "vocab.json").read_text(encoding="utf-8"))
    stoi = {c: i for i, c in enumerate(itos)}
    sess = load_sess()

    print("[corpus] building ...", flush=True)
    seen = mut.build_tier("seen", mut.SEEN_RECITERS, False, PER_OP_SEEN, SEED)
    disj = mut.build_tier("disjoint", mut.UNSEEN_RECITERS, True, PER_OP_DISJ, SEED + 1)
    clean_seen = [dict(x, op="clean", expected_status="CORRECT")
                  for x in mut.build_tier("seen", mut.SEEN_RECITERS, False,
                                          N_CLEAN_SEEN, SEED + 2)[:N_CLEAN_SEEN]]
    clean_disj = [dict(x, op="clean", expected_status="CORRECT")
                  for x in mut.build_tier("disjoint", mut.UNSEEN_RECITERS, True,
                                          N_CLEAN_DISJ, SEED + 3)[:N_CLEAN_DISJ]]
    seen += clean_seen
    disj += clean_disj
    (ROOT / "eval" / "corpus" / "ratl-worderr-v1.json").write_text(
        json.dumps({"seed": SEED, "seen": seen, "disjoint": disj},
                   ensure_ascii=False, indent=1), encoding="utf-8")
    print(f"[corpus] seen {len(seen)} items, disjoint {len(disj)} items", flush=True)

    CACHE = ROOT / "eval" / "g3g4_signals.json"
    if CACHE.exists():
        print("[signals] reusing cache", flush=True)
        c = json.loads(CACHE.read_text(encoding="utf-8"))
        s_seen, s_disj = c["seen"], c["disjoint"]
        for grp in (s_seen, s_disj):
            for r in grp:
                r["shapes"] = {int(k): v for k, v in r["shapes"].items()}
                for sh in r["shapes"].values():
                    sh["words"] = {int(k): v for k, v in sh["words"].items()}
                    sh["garbage"] = {int(k): v for k, v in sh["garbage"].items()}
    else:
        s_seen = score_items(seen, stoi, sess, "seen")
        s_disj = score_items(disj, stoi, sess, "disjoint")
        CACHE.write_text(json.dumps({"seen": s_seen, "disjoint": s_disj},
                                    ensure_ascii=False), encoding="utf-8")
    print(f"[scored] seen {len(s_seen)}, disjoint {len(s_disj)}", flush=True)

    # ---- range the grids from MEASURED signal distributions, seen tier only
    dist = signal_distributions(s_seen)
    print("[signal ranges, seen tier] " + json.dumps(dist), flush=True)

    # ---------- SELECTION: seen tier only ----------
    best, trials = None, []
    for gi in range(len(GRID_SHAPE)):
        for lam_skip, tau_sub, tau_unc in itertools.product(GRID_SKIP, GRID_SUB, GRID_UNC):
            tgt, cw = evaluate(s_seen, gi, lam_skip, tau_sub, tau_unc)
            m = metrics(tgt, cw)
            g3, frr = m["G3_omission_recall"], m["G4_frr"]
            if g3 is None or frr is None:
                continue
            # SPEC 4 rule 1: protect FRR first, then maximise omission recall.
            # Abstention is not free - non_correct_rate guards against buying FRR
            # with blanket UNCERTAIN.
            feasible = frr <= 0.05 and m["non_correct_rate_on_clean"] <= 0.15
            key = (1 if feasible else 0, g3 if feasible else -frr)
            trials.append({"shape": gi, "lam_skip": lam_skip, "tau_sub": tau_sub,
                           "tau_unc": None if tau_unc == -np.inf else tau_unc,
                           "G3": g3, "G4": frr,
                           "non_correct": m["non_correct_rate_on_clean"]})
            if best is None or key > best[0]:
                best = (key, gi, lam_skip, tau_sub, tau_unc, m)
    _, gi, lam_skip, tau_sub, tau_unc, m_seen = best
    sel = {"shape_index": gi, **GRID_SHAPE[gi], "lam_skip": lam_skip,
           "lam_rep": LAM_REP, "tau_sub": tau_sub,
           "tau_uncertain": None if tau_unc == -np.inf else tau_unc}
    print(f"[selected on SEEN tier] {json.dumps(sel)}", flush=True)

    # ---------- HELD-OUT GATE: disjoint tier, run once ----------
    tgt_d, cw_d = evaluate(s_disj, gi, lam_skip, tau_sub, tau_unc)
    m_disj = metrics(tgt_d, cw_d)
    tgt_s, cw_s = evaluate(s_seen, gi, lam_skip, tau_sub, tau_unc)

    def breakdown(tgt, cw):
        conf = {}
        for r in tgt:
            conf.setdefault(r["expected"], {}).setdefault(r["got"], 0)
            conf[r["expected"]][r["got"]] += 1
        conf["CORRECT_clean"] = {}
        for r in cw:
            conf["CORRECT_clean"][r["got"]] = conf["CORRECT_clean"].get(r["got"], 0) + 1
        by_rec = {}
        for r in tgt:
            d = by_rec.setdefault(r["reciter"], {"n": 0, "hit": 0})
            d["n"] += 1
            d["hit"] += int(r["got"] == r["expected"])
        for k, d in by_rec.items():
            d["accuracy"] = round(d["hit"] / d["n"], 4)
        bins = [(0, 500), (500, 1000), (1000, 2000), (2000, 4000), (4000, 10 ** 9)]
        by_dur = {}
        for lo, hi in bins:
            sub = [r for r in tgt if lo <= r["wdur"] < hi]
            if sub:
                by_dur[f"{lo}-{hi if hi < 10**9 else 'inf'}ms"] = {
                    "n": len(sub),
                    "accuracy": round(sum(1 for r in sub if r["got"] == r["expected"])
                                      / len(sub), 4)}
        null_items = [r for r in tgt if r["op"] == "null"]
        return {"confusion": conf, "by_reciter": by_rec, "by_word_duration": by_dur,
                "null_control": {
                    "n": len(null_items),
                    "flagged_as_error": sum(1 for r in null_items if r["got"] in ERRSET),
                    "false_positive_rate": round(
                        sum(1 for r in null_items if r["got"] in ERRSET) / len(null_items), 4)
                    if null_items else None},
                "clustering": {"n_target_words": len(tgt),
                               "n_distinct_clips": len({r["clip"] for r in tgt}),
                               "n_distinct_ayat": len({r["ayah"] for r in tgt}),
                               "n_reciters": len({r["reciter"] for r in tgt}),
                               "n_clean_words": len(cw),
                               "n_clean_clips": len({r["clip"] for r in cw})}}

    rep = {
        "step": "M2.0 G3/G4 reference-lattice evaluation",
        "model": MODEL, "seed": SEED,
        "bench_used_for_tuning": False,
        "selection": {"tier": "seen_reciter ONLY", "config": sel,
                      "objective": "SPEC 4 rule 1 - feasible FRR first, then max "
                                   "omission recall; abstention guarded by "
                                   "non_correct_rate <= 0.15",
                      "n_configs_tried": len(trials)},
        "seen_tier": {**m_seen, **breakdown(tgt_s, cw_s)},
        "disjoint_tier_HELD_OUT": {**m_disj, **breakdown(tgt_d, cw_d)},
        "gates": {
            "G3_threshold": 0.80, "G4_threshold": 0.05,
            "G3_disjoint": m_disj["G3_omission_recall"],
            "G4_disjoint": m_disj["G4_frr"],
            "G3_PASS": bool(m_disj["G3_omission_recall"] is not None
                            and m_disj["G3_omission_recall"] >= 0.80),
            "G4_PASS": bool(m_disj["G4_frr"] is not None and m_disj["G4_frr"] <= 0.05),
        },
    }
    REPORT.write_text(json.dumps(rep, indent=2, ensure_ascii=False), encoding="utf-8")

    print("\n" + "=" * 74)
    print("G3 / G4 - reference lattice")
    print("=" * 74)
    for name, m in (("SEEN (selection)", m_seen), ("DISJOINT (held-out gate)", m_disj)):
        print(f"  {name}")
        print(f"    G3 omission recall {m['G3_omission_recall']}  (n={m['G3_n']})")
        print(f"    G4 FRR             {m['G4_frr']}  (n={m['G4_n_clean_words']} clean words)")
        print(f"    UNCERTAIN on clean {m['uncertain_rate_on_clean']}   "
              f"non-CORRECT {m['non_correct_rate_on_clean']}")
    g = rep["gates"]
    print(f"\n  G3 >= 0.80 : {g['G3_disjoint']}  {'PASS' if g['G3_PASS'] else 'FAIL'}")
    print(f"  G4 <= 0.05 : {g['G4_disjoint']}  {'PASS' if g['G4_PASS'] else 'FAIL'}")


if __name__ == "__main__":
    main()
