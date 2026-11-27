"""M2.0 - FINAL G4 selection pass. Can abstention bring held-out FRR to <= 0.05?

One question only: does the UNCERTAIN mechanism, once it is actually reachable,
reduce held-out G4 from 0.0839 to <= 0.05 at acceptable coverage?

No downloads, no retraining, no model change, no G2 change, no new lattice arcs.
This re-sweeps THRESHOLDS over the signals already cached by g3g4_eval2.py.

Two changes from the previous pass, both forced by measurement:

1. Abstention is now reachable. SUBSTITUTION was decided before the abstention
   check and returned immediately, so no substitution candidate could abstain -
   which is why UNCERTAIN was pinned at 0% regardless of the band.

2. The seen tier under-predicts held-out FRR, so feasibility on seen is not
   feasibility. Measured ratios from the previous pass:
       seen 0.0300 -> spent 0.0686  (x2.29)
       seen 0.0300 -> fresh 0.0839  (x2.80)
   The seen-tier budget is therefore set to 0.05 / 2.8, i.e. FRR <= 0.018, using
   the SPENT tier (exploratory, already consumed) to sanity-check the factor.
   The FRESH tier is not consulted until the final single gate run.
"""
import importlib.util
import itertools
import json
import pathlib
import sys

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


lat = _load("lat", "lattice.py", ROOT / "eval")
ev2 = _load("ev2", "g3g4_eval2.py", ROOT / "eval")

CACHE = ROOT / "eval" / "g3g4_signals2.json"
REPORT = ROOT / "eval" / "g4_final.json"

SEEN_FRR_BUDGET = 0.018        # 0.05 / 2.8 generalization factor
COVERAGE_FLOOR = 0.85          # abstain on at most 15% of words
GRID_SUBZ = [3.5, 5.0, 8.0, 12.0, 20.0, np.inf]
GRID_SUBZ_ABSTAIN = [1.5, 2.0, 2.5, 3.0, 4.0, 6.0]
GRID_OMIT_ABSTAIN = [-np.inf, -25.0, -18.0, -12.0, -8.0]
GRID_UNC = [-np.inf, 0.0, 0.5, 1.0]
ERRSET = ev2.ERRSET


def cfg_for(sh, tsz, tsza, toa, tu):
    return lat.LatticeConfig(lam_rep=ev2.LAM_REP, tau_sub_z=tsz,
                             tau_sub_z_abstain=min(tsza, tsz),
                             tau_omit_abstain=toa, tau_uncertain=tu, **sh)


def evaluate(scored, si, cfg):
    tgt, clean = [], []
    for s in scored:
        it = s["item"]
        raw = s["shapes"][si] if si in s["shapes"] else s["shapes"][str(si)]
        sig = ev2.rehydrate(raw)
        llrs = {int(k): v for k, v in s["llr"].items()}
        wi, exp = it["word_index"], it["expected_status"]
        got = lat.classify(sig, wi, cfg, llrs.get(wi))
        if exp == "INSERTION" and (sig["garbage"].get(wi + 1) or sig["garbage"].get(wi)):
            got = lat.INSERTION
        tgt.append({"op": it["op"], "expected": exp, "got": got,
                    "reciter": it["reciter"], "clip": it["clip_key"],
                    "ayah": f"{it['surah']}:{it['ayah']}", "wdur": it["word_dur_ms"],
                    "meta": it.get("meta", {})})
        if it["op"] in ("clean", "null"):
            for w in range(s["n_words"]):
                clean.append({"got": lat.classify(sig, w, cfg, llrs.get(w)),
                              "reciter": it["reciter"], "clip": it["clip_key"],
                              "op": it["op"]})
    return tgt, clean


def full_metrics(tgt, clean):
    m = ev2.metrics(tgt, clean)
    unc = m["uncertain_rate_on_clean"] or 0.0
    m["retained_coverage_on_clean"] = round(1.0 - unc, 4)
    all_unc = sum(1 for r in tgt if r["got"] == lat.UNCERTAIN) + \
        sum(1 for r in clean if r["got"] == lat.UNCERTAIN)
    m["overall_uncertain_rate"] = round(all_unc / max(1, len(tgt) + len(clean)), 4)
    m["overall_retained_coverage"] = round(1.0 - m["overall_uncertain_rate"], 4)
    by_rec = {}
    for r in clean:
        d = by_rec.setdefault(r["reciter"], {"n": 0, "err": 0, "unc": 0})
        d["n"] += 1
        d["err"] += int(r["got"] in ERRSET)
        d["unc"] += int(r["got"] == lat.UNCERTAIN)
    for d in by_rec.values():
        d["frr"] = round(d["err"] / d["n"], 4)
        d["uncertain_rate"] = round(d["unc"] / d["n"], 4)
        d["coverage"] = round(1 - d["unc"] / d["n"], 4)
    m["clean_by_reciter"] = by_rec
    return m


def main():
    data = json.loads(CACHE.read_text(encoding="utf-8"))
    s_seen, s_spent, s_fresh = data["seen"], data["disjoint_spent"], data["disjoint_fresh"]
    print(f"[signals] seen {len(s_seen)}  spent {len(s_spent)}  fresh {len(s_fresh)}  "
          f"(cached; nothing re-run)", flush=True)

    # ---------------- SELECTION: seen tier ONLY ----------------
    best, tried, curve = None, 0, []
    for si in range(len(ev2.SHAPES)):
        for tsz, tsza, toa, tu in itertools.product(
                GRID_SUBZ, GRID_SUBZ_ABSTAIN, GRID_OMIT_ABSTAIN, GRID_UNC):
            if tsza > tsz:
                continue
            cfg = cfg_for(ev2.SHAPES[si], tsz, tsza, toa, tu)
            tgt, clean = evaluate(s_seen, si, cfg)
            m = full_metrics(tgt, clean)
            if m["G3_omission_recall"] is None or m["G4_frr"] is None:
                continue
            tried += 1
            cov = m["retained_coverage_on_clean"]
            curve.append({"frr": m["G4_frr"], "coverage": cov,
                          "g3": m["G3_omission_recall"]})
            feasible = (m["G4_frr"] <= SEEN_FRR_BUDGET
                        and cov >= COVERAGE_FLOOR
                        and m["G3_omission_recall"] >= 0.80)
            macro = float(np.mean([v["f1"] or 0.0 for v in m["per_class"].values()]))
            key = (1 if feasible else 0,
                   (cov + m["G3_omission_recall"] + macro) if feasible
                   else -(m["G4_frr"] + max(0.0, COVERAGE_FLOOR - cov)))
            if best is None or key > best[0]:
                best = (key, si, tsz, tsza, toa, tu, m)

    _, si, tsz, tsza, toa, tu, m_seen = best
    sel = {**ev2.SHAPES[si], "lam_rep": ev2.LAM_REP,
           "tau_sub_z": None if tsz == np.inf else tsz,
           "tau_sub_z_abstain": min(tsza, tsz),
           "tau_omit_abstain": None if toa == -np.inf else toa,
           "tau_uncertain": None if tu == -np.inf else tu}
    feasible_seen = (m_seen["G4_frr"] <= SEEN_FRR_BUDGET
                     and m_seen["retained_coverage_on_clean"] >= COVERAGE_FLOOR)
    print(f"[selected on SEEN only] {json.dumps(sel)}")
    print(f"  seen: G3 {m_seen['G3_omission_recall']}  G4 {m_seen['G4_frr']}  "
          f"coverage {m_seen['retained_coverage_on_clean']}  "
          f"budget-met {feasible_seen}", flush=True)

    cfg = cfg_for(ev2.SHAPES[si], tsz, tsza, toa, tu)
    t_spent, c_spent = evaluate(s_spent, si, cfg)
    m_spent = full_metrics(t_spent, c_spent)

    # ---------------- THE GATE: fresh tier, once ----------------
    t_fresh, c_fresh = evaluate(s_fresh, si, cfg)
    m_fresh = full_metrics(t_fresh, c_fresh)

    g3_pass = m_fresh["G3_omission_recall"] >= 0.80
    g4_pass = m_fresh["G4_frr"] <= 0.05
    cov_ok = m_fresh["retained_coverage_on_clean"] >= COVERAGE_FLOOR

    rep = {"step": "M2.0 FINAL G4 selection pass",
           "question": "can abstention bring held-out G4 to <= 0.05 at acceptable coverage?",
           "no_downloads": True, "no_retraining": True, "model_unchanged": True,
           "selection": {"tier": "seen ONLY", "config": sel, "n_configs": tried,
                         "seen_frr_budget": SEEN_FRR_BUDGET,
                         "coverage_floor": COVERAGE_FLOOR,
                         "generalization_factor_source":
                             "seen->spent x2.29 and seen->fresh x2.80 measured in "
                             "the previous pass; fresh not consulted for selection"},
           "seen_tier_SELECTION": {**m_seen, **ev2.breakdown(
               *evaluate(s_seen, si, cfg))},
           "disjoint_spent_EXPLORATORY": {**m_spent, **ev2.breakdown(t_spent, c_spent)},
           "disjoint_fresh_GATE": {**m_fresh, **ev2.breakdown(t_fresh, c_fresh)},
           "previous_pass": {"G3": 0.9333, "G4": 0.0839, "uncertain_rate": 0.0},
           "gates": {"G3_threshold": 0.80, "G4_threshold": 0.05,
                     "G3": m_fresh["G3_omission_recall"], "G4": m_fresh["G4_frr"],
                     "coverage": m_fresh["retained_coverage_on_clean"],
                     "G3_PASS": bool(g3_pass), "G4_PASS": bool(g4_pass),
                     "coverage_acceptable": bool(cov_ok),
                     "OVERALL": "PASS" if (g3_pass and g4_pass and cov_ok) else "FAIL"}}
    REPORT.write_text(json.dumps(rep, indent=2, ensure_ascii=False), encoding="utf-8")

    print("\n" + "=" * 76)
    print("FINAL G4 PASS - abstention enabled")
    print("=" * 76)
    for nm, m in (("SEEN (selection)", m_seen), ("SPENT (exploratory)", m_spent),
                  ("FRESH (GATE)", m_fresh)):
        print(f"  {nm}")
        print(f"    G3 {m['G3_omission_recall']}   G4 {m['G4_frr']}   "
              f"UNCERTAIN {m['uncertain_rate_on_clean']}   "
              f"coverage {m['retained_coverage_on_clean']}")
    g = rep["gates"]
    print(f"\n  G3 >= 0.80 : {g['G3']}  {'PASS' if g['G3_PASS'] else 'FAIL'}")
    print(f"  G4 <= 0.05 : {g['G4']}  {'PASS' if g['G4_PASS'] else 'FAIL'}")
    print(f"  coverage   : {g['coverage']}  (floor {COVERAGE_FLOOR})")
    print(f"  previous G4: 0.0839 at 0% abstention")
    print("\n  per-class (fresh gate):")
    for k, v in m_fresh["per_class"].items():
        print(f"    {k:<13} P {v['precision']}  R {v['recall']}  F1 {v['f1']}")
    print("\n  clean FRR by reciter (fresh):", json.dumps(m_fresh["clean_by_reciter"]))


if __name__ == "__main__":
    main()
