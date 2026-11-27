"""M2.0 VALIDATION - does the likelihood-ratio signal separate present from absent?

The free-decode diagnostics closed off string comparison: a word substitution
rate of 0.34 cannot meet a 0.05 false-reject ceiling. The proposed replacement
decides by likelihood ratio instead:

    LLR_i = score(forced path over the FULL target)
          - score(forced path over the target with word i removed)

SIGN (corrected after measurement). I first reasoned that dropping a label can
only help the path, so LLR_i would always be <= 0 and the signal would be its
magnitude. That is wrong: a CTC path covers all T frames whatever the label
count, so removing a word does not relax a frame budget - it forces the frames
that word explained onto blanks or onto neighbouring labels. If word i really
was recited, keeping it therefore SCORES HIGHER and LLR_i is positive; if it was
omitted, forcing it in is expensive and LLR_i is negative. The measured medians
(+107 clean, -100 deleted) follow the corrected reading.

This probe answers one question and nothing else: is that magnitude separable?
It is paired by construction - the same (clip, word) is scored twice, once on the
clean audio and once with exactly that word excised - so the comparison is not
confounded by word identity, reciter, position or duration.

NOT an experiment and NOT a gate. No training, no new model, no decoder change,
no threshold selection, no G2 involvement. The frozen M2E_edge_spike pipeline is
not called here at all: this reads the same logits through a scoring function.

The frozen aligner is NOT modified. viterbi_score() below re-derives the same
path and additionally returns its total log-probability, and every call asserts
its spans are identical to g2.ctc_forced_align's. If that assertion ever fails
the probe aborts rather than reporting a number.
"""
import importlib.util
import json
import pathlib
import random
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

OUT = ROOT / "models" / "out"
REPORT = ROOT / "eval" / "llr_probe.json"
MODEL = "ratl-align-v1-e2e-fp32.onnx"
SEED = 1449
N_PER_TIER = 100          # (clip, word) pairs per tier -> 2x that many scores
MAX_CLIP_S = 15.0
CROSSFADE_MS = 10.0

SEEN_RECITERS = ["Abdul_Basit_Murattal_64kbps", "Abu_Bakr_Ash-Shaatree_128kbps",
                 "Alafasy_128kbps", "Husary_64kbps", "Husary_Muallim_128kbps",
                 "Minshawy_Mujawwad_192kbps", "Mohammad_al_Tablaway_128kbps",
                 "Saood_ash-Shuraym_128kbps"]
UNSEEN_RECITERS = ["Hani_Rifai_192kbps", "Minshawy_Murattal_128kbps"]


def viterbi_score(logp, labels, blank=0):
    """Forced alignment that also returns the total path log-probability.

    Identical recurrence to g2.ctc_forced_align - stay / s-1 / s-2 with the skip
    permitted only into a non-blank state whose label differs from ext[s-2].
    Returns (spans, total_logprob).
    """
    T, L = logp.shape[0], len(labels)
    S = 2 * L + 1
    ext = np.full(S, blank, dtype=np.int64)
    ext[1::2] = labels
    NEG = -1e30
    alpha = np.full(S, NEG, dtype=np.float64)
    alpha[0] = logp[0, ext[0]]
    if S > 1:
        alpha[1] = logp[0, ext[1]]
    back = np.zeros((T, S), dtype=np.int8)
    can_skip = np.zeros(S, dtype=bool)
    if S > 2:
        can_skip[2:] = (ext[2:] != blank) & (ext[2:] != ext[:-2])
    for t in range(1, T):
        a1 = np.concatenate(([NEG], alpha[:-1]))
        a2 = np.where(can_skip, np.concatenate(([NEG, NEG], alpha[:-2])), NEG)
        stack = np.vstack([alpha, a1, a2])
        arg = stack.argmax(axis=0)
        back[t] = arg.astype(np.int8)
        alpha = stack[arg, np.arange(S)] + logp[t, ext]
    s = S - 1 if alpha[S - 1] >= alpha[S - 2] else S - 2
    total = float(alpha[s])
    path = np.zeros(T, dtype=np.int64)
    for t in range(T - 1, -1, -1):
        path[t] = s
        s = s - int(back[t, s])
    spans = []
    for l in range(L):
        occ = np.nonzero(path == 2 * l + 1)[0]
        spans.append((int(occ[0]), int(occ[-1])) if occ.size else None)
    return spans, total


def target_without_word(words, wi, stoi):
    """Token ids for the target with word wi removed, separators kept consistent."""
    kept = [w for j, w in enumerate(words) if j != wi]
    ids, _, _ = e1.build_tokens_v2(kept, stoi)
    return ids


def excise(audio, t0_ms, t1_ms, sr):
    """Delete [t0, t1) with a short linear crossfade at the join."""
    i0, i1 = int(t0_ms / 1000 * sr), int(t1_ms / 1000 * sr)
    i0, i1 = max(0, i0), min(len(audio), i1)
    if i1 <= i0:
        return None
    head, tail = audio[:i0], audio[i1:]
    n = int(CROSSFADE_MS / 1000 * sr)
    n = min(n, len(head), len(tail))
    if n <= 0:
        return np.concatenate([head, tail])
    r = np.linspace(0.0, 1.0, n, dtype=np.float32)
    join = head[-n:] * (1 - r) + tail[:n] * r
    return np.concatenate([head[:-n], join, tail[n:]])


def llr_for(audio, words, wi, stoi, sess):
    """LLR_i on one audio signal. One ONNX call, two Viterbi passes."""
    logits = v2.run_windows(audio, sess)
    logp = g2.log_softmax(logits.astype(np.float64))
    ids_full, spans, _ = e1.build_tokens_v2(words, stoi)
    if not ids_full or spans[wi] is None:
        return None
    sp_a, s_full = viterbi_score(logp, ids_full)
    ref = g2.ctc_forced_align(logp, ids_full, blank=0)
    if sp_a != ref:                      # frozen-aligner equivalence assertion
        raise AssertionError("viterbi_score diverged from g2.ctc_forced_align")
    ids_wo = target_without_word(words, wi, stoi)
    if not ids_wo:
        return None
    _, s_wo = viterbi_score(logp, ids_wo)
    n_tok = spans[wi][1] - spans[wi][0]
    return {"llr": s_full - s_wo, "n_tok": int(n_tok),
            "llr_per_token": (s_full - s_wo) / max(1, n_tok),
            "frames": int(logp.shape[0])}


TR_CACHE = SPIKE / ".cache" / "translit"
CACHED_TRANSLIT = {(int(p.stem[:3]), int(p.stem[3:])) for p in TR_CACHE.glob("*.json")}


def translit_offline(s, a):
    """Read the cached transliteration. NEVER touches the network.

    The connection is metered and downloads need explicit approval, so an
    uncached ayah is skipped rather than fetched.
    """
    p = TR_CACHE / f"{s:03d}{a:03d}.json"
    if not p.exists():
        return None
    return json.loads(p.read_text(encoding="utf-8"))


def build_items(rng, reciters, gt, bench_only, train_index, want, words_per_clip=1):
    """Sample (reciter, surah, ayah, word) from cached audio + cached translit.

    The transliteration cache was populated while building the training corpus,
    so for the seen-reciter tier almost every cached ayah is also a training
    ayah. Rather than filter that tier to nothing, membership is RECORDED per
    item and the tier is reported as the optimistic bound it is.

    words_per_clip > 1 raises the sample count without new audio, at the cost of
    clustering: those items share a clip and are not independent. Reported.
    """
    bench = set(g2.AYAT)
    cand = []
    for rec in reciters:
        rows = gt.get(rec)
        if not rows:
            continue
        adir = SPIKE / ".cache" / "audio" / rec
        if not adir.is_dir():
            continue
        have = {(int(f.stem[:3]), int(f.stem[3:])) for f in adir.glob("*.mp3")}
        for (s, a) in have & CACHED_TRANSLIT:
            if bench_only != ((s, a) in bench):
                continue
            r = rows.get((s, a))
            if not r:
                continue
            segs = [sg for sg in (r.get("segments") or []) if sg[3] > sg[2]]
            if len(segs) < 3:
                continue
            cand.append((rec, s, a, segs))
    rng.shuffle(cand)
    items = []
    for rec, s, a, segs in cand:
        rng.shuffle(segs)
        for sg in segs[:words_per_clip]:
            items.append({"reciter": rec, "surah": s, "ayah": a, "word": int(sg[0]),
                          "gt_start_ms": int(sg[2]), "gt_end_ms": int(sg[3]),
                          "wdur_ms": int(sg[3] - sg[2]),
                          "ayah_in_training": (rec, s, a) in train_index})
            if len(items) >= want:
                return items
    return items


def auc(pos, neg):
    """Mann-Whitney U. pos = clean (should score higher), neg = deleted."""
    x = np.concatenate([pos, neg])
    order = x.argsort()
    ranks = np.empty(len(x), dtype=np.float64)
    ranks[order] = np.arange(1, len(x) + 1)
    i = 0
    xs = x[order]
    while i < len(xs):                      # average ranks over ties
        j = i
        while j + 1 < len(xs) and xs[j + 1] == xs[i]:
            j += 1
        if j > i:
            ranks[order[i:j + 1]] = ranks[order[i:j + 1]].mean()
        i = j + 1
    n1, n2 = len(pos), len(neg)
    u = ranks[:n1].sum() - n1 * (n1 + 1) / 2.0
    return float(u / (n1 * n2))


def roc_read(clean, deleted, target_recall=0.80):
    """Read the curve at G3's recall - a READ, not a selected threshold."""
    thr = np.percentile(deleted, target_recall * 100)   # deleted below thr = recall
    recall = float((deleted <= thr).mean())
    frr = float((clean <= thr).mean())
    return {"note": "curve read at G3 recall 0.80; NOT a tuned production threshold",
            "threshold": round(float(thr), 4),
            "omission_recall": round(recall, 4),
            "implied_clean_flag_rate": round(frr, 4),
            "G4_ceiling": 0.05,
            "G4_plausible_at_this_point": bool(frr <= 0.05)}


def stats(v):
    v = np.asarray(v, dtype=np.float64)
    return {"n": int(v.size), "mean": round(float(v.mean()), 3),
            "std": round(float(v.std()), 3),
            "p05": round(float(np.percentile(v, 5)), 3),
            "p25": round(float(np.percentile(v, 25)), 3),
            "median": round(float(np.percentile(v, 50)), 3),
            "p75": round(float(np.percentile(v, 75)), 3),
            "p95": round(float(np.percentile(v, 95)), 3)}


def main():
    import librosa
    import onnxruntime as ort

    rng = random.Random(SEED)
    itos = json.loads((OUT / "vocab.json").read_text(encoding="utf-8"))
    stoi = {c: i for i, c in enumerate(itos)}
    so = ort.SessionOptions()
    so.intra_op_num_threads = 1
    sess = ort.InferenceSession(str(OUT / MODEL), so, providers=["CPUExecutionProvider"])

    train = json.loads((SPIKE / "corpus" / "exp1b-train.json").read_text(encoding="utf-8"))
    tclips = train["clips"] if isinstance(train, dict) else train
    train_index = {(c["reciter"], c["surah"], c["ayah"]) for c in tclips}

    gt = {}
    for rec in SEEN_RECITERS + UNSEEN_RECITERS:
        f = SPIKE / ".cache" / "quran-align" / f"{rec}.json"
        if not f.exists():
            continue
        raw = f.read_text(encoding="utf-8")
        if not raw.lstrip().startswith("["):
            continue
        gt[rec] = {(r["surah"], r["ayah"]): r for r in json.loads(raw)}

    tiers = {}
    for tier, recs, bench_only, wpc in (
            ("seen_reciter", SEEN_RECITERS, False, 1),
            ("reciter_disjoint", UNSEEN_RECITERS, True, 3)):
        tiers[tier] = build_items(rng, recs, gt, bench_only, train_index,
                                  N_PER_TIER, wpc)
        print(f"[{tier}] sampled {len(tiers[tier])} items")

    results = {}
    t0 = time.time()
    for tier, items in tiers.items():
        rows = []
        for k, it in enumerate(items):
            p = SPIKE / ".cache" / "audio" / it["reciter"] / f"{it['surah']:03d}{it['ayah']:03d}.mp3"
            try:
                audio, _ = librosa.load(str(p), sr=g2.SR, mono=True)
            except Exception:  # noqa: BLE001
                continue
            if len(audio) / g2.SR > MAX_CLIP_S:
                continue
            words = translit_offline(it["surah"], it["ayah"])
            if not words or it["word"] >= len(words):
                continue
            clean = llr_for(audio, words, it["word"], stoi, sess)
            if clean is None:
                continue
            mut = excise(audio, it["gt_start_ms"], it["gt_end_ms"], g2.SR)
            if mut is None or len(mut) < g2.SR // 2:
                continue
            dele = llr_for(mut, words, it["word"], stoi, sess)
            if dele is None:
                continue
            rows.append({**it, "clean_llr": clean["llr"], "clean_per_tok": clean["llr_per_token"],
                         "del_llr": dele["llr"], "del_per_tok": dele["llr_per_token"],
                         "n_tok": clean["n_tok"]})
            if (k + 1) % 25 == 0:
                print(f"      [{tier}] {k+1}/{len(items)}  ({time.time()-t0:.0f}s)", flush=True)

        c_raw = np.array([r["clean_llr"] for r in rows])
        d_raw = np.array([r["del_llr"] for r in rows])
        c_pt = np.array([r["clean_per_tok"] for r in rows])
        d_pt = np.array([r["del_per_tok"] for r in rows])
        paired = float(np.mean(c_raw > d_raw)) if rows else None
        results[tier] = {
            "n_pairs": len(rows),
            "n_reciters": len({r["reciter"] for r in rows}),
            "n_distinct_clips": len({(r["reciter"], r["surah"], r["ayah"]) for r in rows}),
            "ayah_in_training_frac": round(float(np.mean(
                [r.get("ayah_in_training", False) for r in rows])), 4) if rows else None,
            "word_duration_ms": stats([r["wdur_ms"] for r in rows]) if rows else None,
            "raw_llr": {"clean": stats(c_raw), "deleted": stats(d_raw),
                        "AUC": round(auc(c_raw, d_raw), 4)},
            "per_token_llr": {"clean": stats(c_pt), "deleted": stats(d_pt),
                              "AUC": round(auc(c_pt, d_pt), 4)},
            "paired_clean_gt_deleted": round(paired, 4) if paired is not None else None,
            "roc_read_raw": roc_read(c_raw, d_raw),
            "roc_read_per_token": roc_read(c_pt, d_pt),
            "rows": rows,
        }
        a = results[tier]
        print(f"  [{tier}] n={a['n_pairs']}  AUC raw {a['raw_llr']['AUC']}  "
              f"per-token {a['per_token_llr']['AUC']}  paired {a['paired_clean_gt_deleted']}")

    rep = {"step": "M2.0 VALIDATION llr_probe",
           "question": "does LLR separate a recited word from an omitted one?",
           "not_a_gate": "no threshold selected, no G3/G4 verdict, no tuning",
           "model": MODEL, "seed": SEED,
           "frozen_pipeline_touched": False,
           "aligner_equivalence_asserted": True,
           "mutation": f"excise the ground-truth word span, {CROSSFADE_MS} ms crossfade",
           "pairing": "same (clip, word) scored clean and deleted",
           "tiers": {k: {x: y for x, y in v.items() if x != "rows"}
                     for k, v in results.items()}}
    REPORT.write_text(json.dumps(rep, indent=2, ensure_ascii=False), encoding="utf-8")
    (ROOT / "eval" / "llr_probe_rows.json").write_text(
        json.dumps({k: v["rows"] for k, v in results.items()}, ensure_ascii=False),
        encoding="utf-8")
    print("\n" + json.dumps(rep["tiers"], indent=2))


if __name__ == "__main__":
    main()
