"""M2.0 - G3 / G4 after the SUBSTITUTION and INCOMPLETE redesign.

Three tiers, and the distinction between them is the whole methodology:

  seen            8 training reciters. THRESHOLD SELECTION HAPPENS HERE ONLY.
  disjoint_spent  the original 30 bench ayat of the two held-out reciters.
                  Already observed once, so it is EXPLORATORY/DIAGNOSTIC ONLY
                  and can never again serve as a gate. Reported, never selected
                  on, never quoted as the gate result.
  disjoint_fresh  the C3-b download: non-bench ayat from the same two held-out
                  reciters, never seen by any tuning decision. THE GATE. Run
                  once, at the end, with the configuration already fixed.

RATL-Bench-v0 is not used anywhere here - it is the G2 gate.

Decision logic is NOT reimplemented in this file. It calls lattice.classify, so
the reference implementation and the sweep cannot drift apart.
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
e1 = _load("e1", "13_eval_exp1.py")
v2 = _load("v2", "verify_onnx_g2_e2e.py", ROOT / "models")
lat = _load("lat", "lattice.py", ROOT / "eval")
mut = _load("mut", "corpus/mutate.py", ROOT / "eval")

OUT = ROOT / "models" / "out"
MODEL = "ratl-align-v1-e2e-fp32.onnx"
REPORT = ROOT / "eval" / "g3g4_eval2.json"
CACHE = ROOT / "eval" / "g3g4_signals2.json"
SEED = 1460
PER_OP = {"seen": 35, "disjoint_spent": 18, "disjoint_fresh": 30}
N_CLEAN = {"seen": 60, "disjoint_spent": 30, "disjoint_fresh": 60}

# SHAPE parameters change the Viterbi path, so signals are recomputed per shape.
SHAPES = [{"lam_skip": ls, "lam_trunc": lt, "garb_pen": gp, "lam_ins": -20.0}
          for ls in (-40.0, -20.0, 0.0, 20.0)
          for lt in (-40.0, -80.0)
          for gp in (2.0, 4.0)]
LAM_REP = -25.0
GRID_SUBZ = [2.5, 3.5, 5.0, 8.0, 12.0, np.inf]
GRID_UNC = [-np.inf, 0.0, 1.0, 2.0]
ERRSET = {lat.OMISSION, lat.SUBSTITUTION, lat.REPETITION, lat.INSERTION, lat.INCOMPLETE}


def cfg_for(sh, tau_sub_z=np.inf, tau_unc=-np.inf):
    return lat.LatticeConfig(lam_rep=LAM_REP, tau_sub_z=tau_sub_z,
                             tau_uncertain=tau_unc, **sh)


def score(items, stoi, sess, tag):
    scored, t0 = [], time.time()
    for k, it in enumerate(items):
        try:
            audio, _segs, _wi = mut.render(it)
        except Exception:  # noqa: BLE001
            continue
        if audio is None or len(audio) < mut.SR // 2:
            continue
        words = mut.translit_offline(it["surah"], it["ayah"])
        if not words:
            continue
        logp = g2.log_softmax(v2.run_windows(audio, sess).astype(np.float64))
        llrs = lat.llr_all(logp, words, stoi)
        shapes = {}
        for si, sh in enumerate(SHAPES):
            sig = lat.compute_signals(logp, words, stoi, cfg_for(sh))
            if sig is None:
                shapes = None
                break
            shapes[si] = {"cut_word": sig["cut_word"], "n_words": sig["n_words"],
                          "present": {int(a): bool(b) for a, b in sig["present"].items()},
                          "n_rep": {int(a): int(b) for a, b in sig["n_rep"].items()},
                          "sub_z": {int(a): float(b) for a, b in sig["sub_z"].items()},
                          "garbage": {int(a): b for a, b in sig["garbage"].items()}}
        if not shapes:
            continue
        scored.append({"item": it, "n_words": len(words), "shapes": shapes,
                       "llr": {int(a): b["per_token"] for a, b in llrs.items()}})
        if (k + 1) % 50 == 0:
            print(f"      [{tag}] {k+1}/{len(items)}  ({time.time()-t0:.0f}s)", flush=True)
    return scored


def rehydrate(sh):
    return {"cut_word": sh["cut_word"], "n_words": sh["n_words"],
            "present": {int(k): v for k, v in sh["present"].items()},
            "n_rep": {int(k): v for k, v in sh["n_rep"].items()},
            "sub_z": {int(k): v for k, v in sh["sub_z"].items()},
            "garbage": {int(k): v for k, v in sh["garbage"].items()}}


def evaluate(scored, si, tau_sub_z, tau_unc):
    cfg = cfg_for(SHAPES[si], tau_sub_z, tau_unc)
    tgt, clean = [], []
    for s in scored:
        it = s["item"]
        sig = rehydrate(s["shapes"][si] if si in s["shapes"] else s["shapes"][str(si)])
        wi, exp = it["word_index"], it["expected_status"]
        llrs = {int(k): v for k, v in s["llr"].items()}
        got = lat.classify(sig, wi, cfg, llrs.get(wi))
        if exp == "INSERTION":
            if sig["garbage"].get(wi + 1) or sig["garbage"].get(wi):
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


def metrics(tgt, clean):
    dele = [r for r in tgt if r["op"] == "delete"]
    g3 = (sum(1 for r in dele if r["got"] == lat.OMISSION) / len(dele)) if dele else None
    frr = (sum(1 for r in clean if r["got"] in ERRSET) / len(clean)) if clean else None
    unc = (sum(1 for r in clean if r["got"] == lat.UNCERTAIN) / len(clean)) if clean else None
    nc = (sum(1 for r in clean if r["got"] != lat.CORRECT) / len(clean)) if clean else None
    per = {}
    for cls in sorted(ERRSET):
        tp = sum(1 for r in tgt if r["expected"] == cls and r["got"] == cls)
        fn = sum(1 for r in tgt if r["expected"] == cls and r["got"] != cls)
        fp = (sum(1 for r in tgt if r["expected"] != cls and r["got"] == cls)
              + sum(1 for r in clean if r["got"] == cls))
        p = tp / (tp + fp) if tp + fp else None
        rc = tp / (tp + fn) if tp + fn else None
        per[cls] = {"tp": tp, "fp": fp, "fn": fn,
                    "precision": None if p is None else round(p, 4),
                    "recall": None if rc is None else round(rc, 4),
                    "f1": None if not (p and rc) else round(2 * p * rc / (p + rc), 4)}
    return {"G3_omission_recall": None if g3 is None else round(g3, 4), "G3_n": len(dele),
            "G4_frr": None if frr is None else round(frr, 4),
            "G4_n_clean_words": len(clean),
            "uncertain_rate_on_clean": None if unc is None else round(unc, 4),
            "non_correct_rate_on_clean": None if nc is None else round(nc, 4),
            "per_class": per}


def breakdown(tgt, clean):
    conf = {}
    for r in tgt:
        conf.setdefault(r["expected"], {})
        conf[r["expected"]][r["got"]] = conf[r["expected"]].get(r["got"], 0) + 1
    conf["CORRECT_clean"] = {}
    for r in clean:
        conf["CORRECT_clean"][r["got"]] = conf["CORRECT_clean"].get(r["got"], 0) + 1
    by_rec = {}
    for r in tgt:
        d = by_rec.setdefault(r["reciter"], {"n": 0, "hit": 0})
        d["n"] += 1
        d["hit"] += int(r["got"] == r["expected"])
    for d in by_rec.values():
        d["accuracy"] = round(d["hit"] / d["n"], 4)
    by_dur = {}
    for lo, hi in [(0, 500), (500, 1000), (1000, 2000), (2000, 4000), (4000, 10 ** 9)]:
        sub = [r for r in tgt if lo <= r["wdur"] < hi]
        if sub:
            by_dur[f"{lo}-{hi if hi < 10**9 else 'inf'}ms"] = {
                "n": len(sub),
                "accuracy": round(sum(1 for r in sub if r["got"] == r["expected"]) / len(sub), 4)}
    trunc = {}
    for band in ("truncate_25", "truncate_50", "truncate_75"):
        sub = [r for r in tgt if r["op"] == band]
        if sub:
            trunc[band] = {"n": len(sub),
                           "recall": round(sum(1 for r in sub if r["got"] == lat.INCOMPLETE)
                                           / len(sub), 4)}
            for cls in ("short", "long"):
                ss = [r for r in sub if r["meta"].get("word_len_class") == cls]
                if ss:
                    trunc[band][cls] = {
                        "n": len(ss),
                        "recall": round(sum(1 for r in ss if r["got"] == lat.INCOMPLETE)
                                        / len(ss), 4)}
    nul = [r for r in tgt if r["op"] == "null"]
    return {"confusion": conf, "by_reciter": by_rec, "by_word_duration": by_dur,
            "truncation_by_position": trunc,
            "null_control": {"n": len(nul),
                             "flagged_as_error": sum(1 for r in nul if r["got"] in ERRSET),
                             "false_positive_rate": round(
                                 sum(1 for r in nul if r["got"] in ERRSET) / len(nul), 4)
                             if nul else None},
            "clustering": {"n_target_words": len(tgt),
                           "n_distinct_clips": len({r["clip"] for r in tgt}),
                           "n_distinct_ayat": len({r["ayah"] for r in tgt}),
                           "n_reciters": len({r["reciter"] for r in tgt}),
                           "n_clean_words": len(clean),
                           "n_clean_clips": len({r["clip"] for r in clean}),
                           "effective_n_note": "mutations from the same clip are not "
                                               "independent; compare n_distinct_clips "
                                               "to n_target_words"}}


def main():
    import onnxruntime as ort
    stoi = {c: i for i, c in enumerate(
        json.loads((OUT / "vocab.json").read_text(encoding="utf-8")))}
    so = ort.SessionOptions()
    so.intra_op_num_threads = 1
    sess = ort.InferenceSession(str(OUT / MODEL), so, providers=["CPUExecutionProvider"])

    if CACHE.exists():
        print("[signals] reusing cache", flush=True)
        data = json.loads(CACHE.read_text(encoding="utf-8"))
    else:
        tiers = {}
        for name, recs, bench_only in (
                ("seen", mut.SEEN_RECITERS, False),
                ("disjoint_spent", mut.UNSEEN_RECITERS, True),
                ("disjoint_fresh", mut.UNSEEN_RECITERS, False)):
            items = mut.build_tier(name, recs, bench_only, PER_OP[name], SEED)
            clean = [dict(x, op="clean", expected_status="CORRECT")
                     for x in mut.build_tier(name, recs, bench_only,
                                             N_CLEAN[name], SEED + 7)[:N_CLEAN[name]]]
            tiers[name] = items + clean
            print(f"[corpus] {name}: {len(tiers[name])} items", flush=True)
        (ROOT / "eval" / "corpus" / "ratl-worderr-v1.json").write_text(
            json.dumps({"seed": SEED, **tiers}, ensure_ascii=False, indent=1),
            encoding="utf-8")
        data = {k: score(v, stoi, sess, k) for k, v in tiers.items()}
        CACHE.write_text(json.dumps(data, ensure_ascii=False), encoding="utf-8")

    s_seen, s_spent, s_fresh = data["seen"], data["disjoint_spent"], data["disjoint_fresh"]
    print(f"[scored] seen {len(s_seen)}  spent {len(s_spent)}  fresh {len(s_fresh)}",
          flush=True)

    # ---------------- SELECTION: seen tier ONLY ----------------
    best, n_tried = None, 0
    for si in range(len(SHAPES)):
        for tsz, tu in itertools.product(GRID_SUBZ, GRID_UNC):
            tgt, clean = evaluate(s_seen, si, tsz, tu)
            m = metrics(tgt, clean)
            if m["G3_omission_recall"] is None or m["G4_frr"] is None:
                continue
            n_tried += 1
            feasible = m["G4_frr"] <= 0.05 and m["non_correct_rate_on_clean"] <= 0.15
            macro = np.mean([v["f1"] or 0.0 for v in m["per_class"].values()])
            key = (1 if feasible else 0,
                   (m["G3_omission_recall"] + macro) if feasible else -m["G4_frr"])
            if best is None or key > best[0]:
                best = (key, si, tsz, tu, m)
    _, si, tsz, tu, m_seen = best
    sel = {**SHAPES[si], "lam_rep": LAM_REP,
           "tau_sub_z": None if tsz == np.inf else tsz,
           "tau_uncertain": None if tu == -np.inf else tu}
    print(f"[selected on SEEN only] {json.dumps(sel)}", flush=True)

    # ---------------- GATE: fresh disjoint tier, run once ----------------
    t_seen, c_seen = evaluate(s_seen, si, tsz, tu)
    t_spent, c_spent = evaluate(s_spent, si, tsz, tu)
    t_fresh, c_fresh = evaluate(s_fresh, si, tsz, tu)
    m_spent, m_fresh = metrics(t_spent, c_spent), metrics(t_fresh, c_fresh)

    rep = {"step": "M2.0 G3/G4 after SUBSTITUTION + INCOMPLETE redesign",
           "model": MODEL, "seed": SEED, "bench_used_for_tuning": False,
           "selection": {"tier": "seen ONLY", "config": sel, "n_configs": n_tried,
                         "objective": "SPEC 4 rule 1: feasible FRR (<=0.05) and "
                                      "bounded abstention first, then omission "
                                      "recall + macro-F1"},
           "seen_tier_SELECTION": {**m_seen, **breakdown(t_seen, c_seen)},
           "disjoint_spent_EXPLORATORY_ONLY": {**m_spent, **breakdown(t_spent, c_spent)},
           "disjoint_fresh_GATE": {**m_fresh, **breakdown(t_fresh, c_fresh)},
           "gates": {"G3_threshold": 0.80, "G4_threshold": 0.05,
                     "G3": m_fresh["G3_omission_recall"], "G4": m_fresh["G4_frr"],
                     "G3_PASS": bool(m_fresh["G3_omission_recall"] is not None
                                     and m_fresh["G3_omission_recall"] >= 0.80),
                     "G4_PASS": bool(m_fresh["G4_frr"] is not None
                                     and m_fresh["G4_frr"] <= 0.05),
                     "measured_on": "disjoint_fresh (C3-b), held out until this run"}}
    REPORT.write_text(json.dumps(rep, indent=2, ensure_ascii=False), encoding="utf-8")

    print("\n" + "=" * 76)
    print("G3 / G4 - redesigned lattice")
    print("=" * 76)
    for nm, m in (("SEEN (selection)", m_seen),
                  ("DISJOINT-SPENT (exploratory)", m_spent),
                  ("DISJOINT-FRESH (GATE)", m_fresh)):
        print(f"  {nm}")
        print(f"    G3 {m['G3_omission_recall']}  (n={m['G3_n']})    "
              f"G4 {m['G4_frr']}  (n={m['G4_n_clean_words']})    "
              f"UNC {m['uncertain_rate_on_clean']}")
    g = rep["gates"]
    print(f"\n  G3 >= 0.80 : {g['G3']}  {'PASS' if g['G3_PASS'] else 'FAIL'}")
    print(f"  G4 <= 0.05 : {g['G4']}  {'PASS' if g['G4_PASS'] else 'FAIL'}")
    print("\n  per-class (fresh gate):")
    for k, v in m_fresh["per_class"].items():
        print(f"    {k:<13} P {v['precision']}  R {v['recall']}  F1 {v['f1']}")


if __name__ == "__main__":
    main()
