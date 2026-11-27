"""M2.0 DIAGNOSTIC - free-decode quality of the frozen exp2 checkpoint.

Question, and the only question: is

    CTC free decode -> split on '|' -> edit-distance alignment to the target

a viable basis for the G3/G4 reference error detector?

This is a MEASUREMENT, not an experiment. No training, no new model, no decoder
tuning, no thresholds, no change to the frozen alignment pipeline. The frozen
forced aligner is not touched here at all - free decode is a *different* read of
the same logits, used only to decide whether an error-tolerant lattice is
buildable on top of it.

Corpus is RATL-Bench-v0 because it is cached, spans 5 reciters, and its 30 ayat
were excluded from training for every reciter - so it reads cleanly as unseen
text. Two of the five reciters (Hani_Rifai, Minshawy_Murattal) were also held out
of training entirely, which gives the seen/unseen contrast for free. Nothing here
selects or tunes anything, so the G2 gate is untouched.
"""
import importlib.util
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

OUT = ROOT / "models" / "out"
REPORT = ROOT / "eval" / "freedecode_wer.json"
MODEL = "ratl-align-v1-e2e-fp32.onnx"

# held out of the 30 h training corpus entirely
UNSEEN = {"Hani_Rifai_192kbps", "Minshawy_Murattal_128kbps"}


def levenshtein(ref, hyp):
    """Edit distance with S/D/I counts via backtrace. ref/hyp are sequences."""
    n, m = len(ref), len(hyp)
    d = np.zeros((n + 1, m + 1), dtype=np.int32)
    d[:, 0] = np.arange(n + 1)
    d[0, :] = np.arange(m + 1)
    for i in range(1, n + 1):
        for j in range(1, m + 1):
            cost = 0 if ref[i - 1] == hyp[j - 1] else 1
            d[i, j] = min(d[i - 1, j] + 1, d[i, j - 1] + 1, d[i - 1, j - 1] + cost)
    i, j, S = n, m, 0
    D = I = 0
    while i > 0 or j > 0:
        if i > 0 and j > 0 and d[i, j] == d[i - 1, j - 1] + (0 if ref[i - 1] == hyp[j - 1] else 1):
            if ref[i - 1] != hyp[j - 1]:
                S += 1
            i, j = i - 1, j - 1
        elif i > 0 and d[i, j] == d[i - 1, j] + 1:
            D += 1
            i -= 1
        else:
            I += 1
            j -= 1
    return int(d[n, m]), S, D, I


def greedy_decode(logits, blank=0):
    """CTC best path: argmax, collapse consecutive repeats, drop blanks."""
    ids = logits.argmax(-1)
    out = []
    prev = -1
    for k in ids:
        k = int(k)
        if k != prev and k != blank:
            out.append(k)
        prev = k
    return out


def main():
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

    rows = []
    t0 = time.time()
    for rec, (_, speed) in g2.RECITERS.items():
        for s, a in g2.AYAT:
            if not gt[rec].get((s, a)):
                continue
            try:
                ap = g2.fetch_audio(rec, s, a)
                audio, _ = librosa.load(str(ap), sr=g2.SR, mono=True)
            except Exception:  # noqa: BLE001
                continue
            words = translit[(s, a)]
            tgt_words = [(w["tr"] or "").strip().lower() for w in words]
            tgt_words = [w for w in tgt_words if w]
            if not tgt_words:
                continue

            logits = v2.run_windows(audio, sess)
            hyp_ids = greedy_decode(logits)

            # reconstruct words by splitting the decoded token stream on '|'
            hyp_words, cur = [], []
            for k in hyp_ids:
                if k == SEP:
                    hyp_words.append("".join(itos[c] for c in cur))
                    cur = []
                else:
                    cur.append(k)
            hyp_words.append("".join(itos[c] for c in cur))
            hyp_words = [w for w in hyp_words if w]

            # word-level
            wd, wS, wD, wI = levenshtein(tgt_words, hyp_words)
            # char-level, separators excluded so it measures phonetic content only
            ref_c = "".join(tgt_words)
            hyp_c = "".join(hyp_words)
            cd, cS, cD, cI = levenshtein(ref_c, hyp_c)

            rows.append({
                "reciter": rec, "speed": speed, "seen_in_training": rec not in UNSEEN,
                "surah": s, "ayah": a,
                "n_tgt_words": len(tgt_words), "n_hyp_words": len(hyp_words),
                "n_sep_expected": len(tgt_words) - 1,
                "n_sep_decoded": sum(1 for k in hyp_ids if k == SEP),
                "wer_edits": wd, "wS": wS, "wD": wD, "wI": wI,
                "n_ref_words": len(tgt_words),
                "cer_edits": cd, "cS": cS, "cD": cD, "cI": cI,
                "n_ref_chars": len(ref_c),
                "exact_word_count": len(tgt_words) == len(hyp_words),
                "tgt": tgt_words, "hyp": hyp_words,
            })
        print(f"      {rec:<34} ({time.time()-t0:.0f}s)", flush=True)

    def agg(sel):
        r = [x for x in rows if sel(x)]
        if not r:
            return None
        nw = sum(x["n_ref_words"] for x in r)
        nc = sum(x["n_ref_chars"] for x in r)
        sep_e = sum(x["n_sep_expected"] for x in r)
        sep_d = sum(x["n_sep_decoded"] for x in r)
        return {
            "n_clips": len(r), "n_ref_words": nw, "n_ref_chars": nc,
            "WER": round(sum(x["wer_edits"] for x in r) / nw, 4),
            "word_sub_rate": round(sum(x["wS"] for x in r) / nw, 4),
            "word_del_rate": round(sum(x["wD"] for x in r) / nw, 4),
            "word_ins_rate": round(sum(x["wI"] for x in r) / nw, 4),
            "CER": round(sum(x["cer_edits"] for x in r) / nc, 4),
            "char_sub_rate": round(sum(x["cS"] for x in r) / nc, 4),
            "char_del_rate": round(sum(x["cD"] for x in r) / nc, 4),
            "char_ins_rate": round(sum(x["cI"] for x in r) / nc, 4),
            "sep_expected": sep_e, "sep_decoded": sep_d,
            "sep_ratio": round(sep_d / sep_e, 4) if sep_e else None,
            "clips_with_exact_word_count": round(
                float(np.mean([x["exact_word_count"] for x in r])), 4),
            "word_count_delta_median": float(np.median(
                [x["n_hyp_words"] - x["n_tgt_words"] for x in r])),
        }

    overall = agg(lambda x: True)
    by_rec = {rec: agg(lambda x, r=rec: x["reciter"] == r)
              for rec in sorted({x["reciter"] for x in rows})}
    by_seen = {"unseen_reciters": agg(lambda x: not x["seen_in_training"]),
               "seen_reciters": agg(lambda x: x["seen_in_training"])}

    worst = sorted(rows, key=lambda x: -x["wer_edits"] / max(1, x["n_ref_words"]))[:5]
    best = sorted(rows, key=lambda x: x["wer_edits"] / max(1, x["n_ref_words"]))[:3]

    rep = {
        "step": "M2.0 DIAGNOSTIC free-decode WER",
        "purpose": "decide viability of free decode -> '|' split -> edit-distance "
                   "alignment as the basis for the G3/G4 reference detector",
        "not_an_experiment": "no training, no tuning, no threshold selection, "
                             "frozen alignment pipeline untouched",
        "model": MODEL,
        "decode": "greedy CTC best path (argmax, collapse repeats, drop blank)",
        "windowing": "FROZEN 15 s / 10 s / 2.5 s",
        "corpus": "RATL-Bench-v0 clips (30 ayat x 5 reciters); ayat excluded from "
                  "training for all reciters",
        "overall": overall,
        "by_seen_in_training": by_seen,
        "by_reciter": by_rec,
        "examples_worst": [{"reciter": x["reciter"], "ayah": f"{x['surah']}:{x['ayah']}",
                            "wer": round(x["wer_edits"] / x["n_ref_words"], 3),
                            "tgt": x["tgt"], "hyp": x["hyp"]} for x in worst],
        "examples_best": [{"reciter": x["reciter"], "ayah": f"{x['surah']}:{x['ayah']}",
                           "wer": round(x["wer_edits"] / x["n_ref_words"], 3),
                           "tgt": x["tgt"], "hyp": x["hyp"]} for x in best],
    }
    REPORT.write_text(json.dumps(rep, indent=2, ensure_ascii=False), encoding="utf-8")

    print("\n" + "=" * 74)
    print("FREE-DECODE DIAGNOSTIC (frozen exp2, e2e fp32) - RATL-Bench-v0")
    print("=" * 74)
    o = overall
    print(f"  WER {o['WER']:.4f}   S {o['word_sub_rate']:.4f}  D {o['word_del_rate']:.4f}"
          f"  I {o['word_ins_rate']:.4f}   ({o['n_ref_words']} ref words)")
    print(f"  CER {o['CER']:.4f}   S {o['char_sub_rate']:.4f}  D {o['char_del_rate']:.4f}"
          f"  I {o['char_ins_rate']:.4f}")
    print(f"  separators decoded/expected {o['sep_decoded']}/{o['sep_expected']}"
          f"  ratio {o['sep_ratio']}")
    print(f"  clips with exact word count {o['clips_with_exact_word_count']*100:.1f}%")
    print("\n  by reciter:")
    for k, v in by_rec.items():
        tag = "seen  " if k not in UNSEEN else "UNSEEN"
        print(f"    {tag} {k:<32} WER {v['WER']:.4f}  CER {v['CER']:.4f}  "
              f"sep {v['sep_ratio']}")


if __name__ == "__main__":
    main()
