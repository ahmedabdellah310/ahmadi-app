"""M2.0 DIAGNOSTIC (2) - how much of the 0.5097 free-decode WER is notational?

The first diagnostic showed WER 0.5097 against CER 0.1258, and the worst-WER
clips were things like:

    al-rahmanu -> al-rahmani     one terminal vowel: WAQF (pausal form)
    ya-seen    -> yaseena        quran.com transliteration hyphenation

SPEC 4 rule 4 says pauses are never errors, and SPEC 5.5 makes normalization the
Normalizer's job. So the raw WER conflates recognition failure with scoring
convention. This script quantifies the split.

Re-scores the SAME decoded strings under four normalization tiers. No model, no
decoder, no threshold, no training - string re-scoring only. Normalization is
applied IDENTICALLY to reference and hypothesis, so each tier is a fair
comparison rather than a way of flattering the model.

  N0  raw                     what the first diagnostic reported
  N1  orthographic            hyphens removed
  N2  N1 + pausal             trailing tanwin / short vowel stripped  <-- waqf
  N3  N2 + aggressive         long/short vowels merged, doubled consonants
                              collapsed, hamza/ayn marks dropped

N3 is a DIAGNOSTIC CEILING, not a proposal. It deliberately destroys distinctions
that a real recitation checker must keep, and is included only to bound how much
of the residual is orthographic at all. Production normalization must never be
this aggressive - it would mask genuine substitutions and inflate false accepts.

Decoded pairs are cached to freedecode_pairs.json so future re-scoring costs
nothing.
"""
import importlib.util
import json
import pathlib
import re
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
m1 = _load("m1", "20_m1_select.py")
v2 = _load("v2", "verify_onnx_g2_e2e.py", ROOT / "models")
fd = _load("fd", "freedecode_wer.py", ROOT / "eval")

OUT = ROOT / "models" / "out"
PAIRS = ROOT / "eval" / "freedecode_pairs.json"
REPORT = ROOT / "eval" / "freedecode_norm.json"
MODEL = "ratl-align-v1-e2e-fp32.onnx"
UNSEEN = fd.UNSEEN


# ---------------------------------------------------------------- normalizers
def n0(w):
    return w


def n1(w):
    """Orthographic only: the transliteration's hyphenation is not phonetic."""
    return w.replace("-", "")


_PAUSAL = re.compile(r"(?:[aiu]n|[aiu])$")


def n2(w):
    """N1 plus the pausal ending. Waqf changes the final vowel at a stop."""
    w = n1(w)
    return _PAUSAL.sub("", w) or w


_LONG = str.maketrans({"ā": "a", "ī": "i", "ū": "u"})


def n3(w):
    """DIAGNOSTIC CEILING - destroys real distinctions. Never ship this."""
    w = n2(w).translate(_LONG)
    w = w.replace("'", "").replace("ʿ", "")
    return re.sub(r"(.)\1+", r"\1", w) or w


TIERS = [("N0_raw", n0), ("N1_orthographic", n1),
         ("N2_pausal", n2), ("N3_aggressive_ceiling", n3)]


def decode_pairs():
    """Decode once and cache. Identical code path to the first diagnostic."""
    if PAIRS.exists():
        print(f"[reusing {PAIRS.name}]")
        return json.loads(PAIRS.read_text(encoding="utf-8"))

    import librosa
    import onnxruntime as ort

    itos = json.loads((OUT / "vocab.json").read_text(encoding="utf-8"))
    stoi = {c: i for i, c in enumerate(itos)}
    SEP = stoi["|"]
    so = ort.SessionOptions()
    so.intra_op_num_threads = 1
    sess = ort.InferenceSession(str(OUT / MODEL), so, providers=["CPUExecutionProvider"])

    gt = g2.load_gt()
    translit = {k: g2.fetch_translit(*k) for k in g2.AYAT}
    rows, t0 = [], time.time()
    for rec, (_, speed) in g2.RECITERS.items():
        for s, a in g2.AYAT:
            if not gt[rec].get((s, a)):
                continue
            try:
                ap = g2.fetch_audio(rec, s, a)
                audio, _ = librosa.load(str(ap), sr=g2.SR, mono=True)
            except Exception:  # noqa: BLE001
                continue
            tgt = [(w["tr"] or "").strip().lower() for w in translit[(s, a)]]
            tgt = [w for w in tgt if w]
            if not tgt:
                continue
            ids = fd.greedy_decode(v2.run_windows(audio, sess))
            hyp, cur = [], []
            for k in ids:
                if k == SEP:
                    hyp.append("".join(itos[c] for c in cur))
                    cur = []
                else:
                    cur.append(k)
            hyp.append("".join(itos[c] for c in cur))
            rows.append({"reciter": rec, "speed": speed,
                         "seen_in_training": rec not in UNSEEN,
                         "surah": s, "ayah": a, "tgt": tgt,
                         "hyp": [w for w in hyp if w]})
        print(f"      {rec:<34} ({time.time()-t0:.0f}s)", flush=True)
    PAIRS.write_text(json.dumps(rows, ensure_ascii=False), encoding="utf-8")
    return rows


def score(rows, norm, sel):
    r = [x for x in rows if sel(x)]
    if not r:
        return None
    W = dict(e=0, S=0, D=0, I=0, n=0)
    C = dict(e=0, S=0, D=0, I=0, n=0)
    exact = []
    for x in r:
        t = [norm(w) for w in x["tgt"]]
        h = [norm(w) for w in x["hyp"]]
        e, S, D, I = fd.levenshtein(t, h)
        W["e"] += e; W["S"] += S; W["D"] += D; W["I"] += I; W["n"] += len(t)
        tc, hc = "".join(t), "".join(h)
        e, S, D, I = fd.levenshtein(tc, hc)
        C["e"] += e; C["S"] += S; C["D"] += D; C["I"] += I; C["n"] += len(tc)
        exact.append(sum(1 for a, b in zip(t, h) if a == b) if len(t) == len(h) else None)
    aligned = [v for v in exact if v is not None]
    return {"n_clips": len(r), "n_ref_words": W["n"],
            "WER": round(W["e"] / W["n"], 4),
            "word_sub_rate": round(W["S"] / W["n"], 4),
            "word_del_rate": round(W["D"] / W["n"], 4),
            "word_ins_rate": round(W["I"] / W["n"], 4),
            "CER": round(C["e"] / C["n"], 4),
            "exact_word_match_rate_when_counts_agree": round(
                sum(aligned) / sum(len([w for w in x["tgt"]]) for x, v in zip(r, exact)
                                   if v is not None), 4) if aligned else None}


def main():
    rows = decode_pairs()
    rep = {"step": "M2.0 DIAGNOSTIC free-decode normalization re-scoring",
           "not_an_experiment": "string re-scoring only; no model, decoder, "
                                "threshold or training touched",
           "model": MODEL, "n_clips": len(rows),
           "tier_definitions": {
               "N0_raw": "as decoded",
               "N1_orthographic": "hyphens removed",
               "N2_pausal": "N1 + trailing tanwin/short vowel stripped (waqf)",
               "N3_aggressive_ceiling": "N2 + long vowels merged, doubled consonants "
                                        "collapsed, hamza/ayn dropped - DIAGNOSTIC "
                                        "CEILING ONLY, must never ship"},
           "overall": {}, "unseen_reciters": {}, "seen_reciters": {}, "by_reciter": {}}

    for name, fn in TIERS:
        rep["overall"][name] = score(rows, fn, lambda x: True)
        rep["unseen_reciters"][name] = score(rows, fn, lambda x: not x["seen_in_training"])
        rep["seen_reciters"][name] = score(rows, fn, lambda x: x["seen_in_training"])
    for rec in sorted({x["reciter"] for x in rows}):
        rep["by_reciter"][rec] = {
            name: score(rows, fn, lambda x, r=rec: x["reciter"] == r)
            for name, fn in TIERS}

    o = rep["overall"]
    rep["attribution"] = {
        "raw_WER": o["N0_raw"]["WER"],
        "explained_by_hyphenation": round(o["N0_raw"]["WER"] - o["N1_orthographic"]["WER"], 4),
        "explained_by_pausal_waqf": round(o["N1_orthographic"]["WER"] - o["N2_pausal"]["WER"], 4),
        "explained_by_aggressive_ceiling": round(o["N2_pausal"]["WER"] - o["N3_aggressive_ceiling"]["WER"], 4),
        "residual_after_N2": o["N2_pausal"]["WER"],
        "residual_after_N3_ceiling": o["N3_aggressive_ceiling"]["WER"],
    }
    REPORT.write_text(json.dumps(rep, indent=2, ensure_ascii=False), encoding="utf-8")

    print("\n" + "=" * 78)
    print("NORMALIZATION RE-SCORING - free decode, frozen exp2, RATL-Bench-v0")
    print("=" * 78)
    print(f"  {'tier':<24} {'WER':>7} {'S':>7} {'D':>7} {'I':>7} {'CER':>7}")
    for name, _ in TIERS:
        v = o[name]
        print(f"  {name:<24} {v['WER']:>7.4f} {v['word_sub_rate']:>7.4f} "
              f"{v['word_del_rate']:>7.4f} {v['word_ins_rate']:>7.4f} {v['CER']:>7.4f}")
    print("\n  seen vs unseen reciters:")
    for name, _ in TIERS:
        s, u = rep["seen_reciters"][name], rep["unseen_reciters"][name]
        print(f"    {name:<24} seen WER {s['WER']:.4f}   unseen WER {u['WER']:.4f}")
    print("\n  attribution:", json.dumps(rep["attribution"], indent=None))


if __name__ == "__main__":
    main()
