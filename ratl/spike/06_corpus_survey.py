"""
RATL Week 1 spike — Rung 3, step 1: what training labels can quran-align + everyayah
actually yield?

MEASURED survey only. No training. No model changes.
RATL-Bench-v0 and the alignment harness are NOT modified.
"""

import json
import pathlib

HERE = pathlib.Path(__file__).parent
GT = HERE / ".cache" / "quran-align"
RESULTS = HERE / "results"

# Sudais excluded: corrupt upstream file (DAY3_NOTES.md section 6).
EXCLUDE = {"Abdurrahmaan_As-Sudais_192kbps.json"}

# RATL-Bench-v0 test ayat -- MUST be held out of any training split.
BENCH_AYAT = {
    (1, 1), (1, 2), (1, 5), (1, 7), (2, 1), (2, 5), (2, 255), (3, 8),
    (4, 1), (6, 1), (12, 1), (18, 1), (18, 10), (24, 35), (36, 1), (36, 20),
    (55, 1), (55, 13), (67, 1), (78, 1), (87, 1), (91, 1), (93, 1), (94, 1),
    (96, 1), (97, 1), (103, 1), (108, 1), (112, 1), (114, 1),
}
BENCH_RECITERS = {
    "Husary_64kbps", "Alafasy_128kbps", "Hani_Rifai_192kbps",
    "Abdul_Basit_Mujawwad_128kbps", "Minshawy_Murattal_128kbps",
}


def survey():
    out = {"reciters": {}, "excluded": sorted(EXCLUDE)}
    tot_ayat = tot_words = 0
    tot_ms = 0.0
    gap_stats = []

    for f in sorted(GT.glob("*.json")):
        if f.name in EXCLUDE:
            continue
        raw = f.read_text(encoding="utf-8")
        if not raw.lstrip().startswith("["):
            out["reciters"][f.stem] = {"error": "corrupt, not a JSON array"}
            continue
        rows = json.loads(raw)

        n_ayat = len(rows)
        n_words = 0
        dur_ms = 0.0
        n_ins = n_del = n_trans = 0
        clean_ayat = 0
        word_durs = []

        for r in rows:
            segs = r.get("segments") or []
            if not segs:
                continue
            n_words += len(segs)
            dur_ms += segs[-1][3] - segs[0][2]
            st = r.get("stats", {})
            i, d, t = st.get("insertions", 0), st.get("deletions", 0), st.get("transpositions", 0)
            n_ins += i
            n_del += d
            n_trans += t
            if i == d == t == 0:
                clean_ayat += 1
            for s in segs:
                word_durs.append(s[3] - s[2])
            for a, b in zip(segs, segs[1:]):
                gap_stats.append(b[2] - a[3])

        word_durs.sort()
        out["reciters"][f.stem] = {
            "ayat": n_ayat,
            "ayat_zero_error_stats": clean_ayat,
            "pct_ayat_clean": round(100 * clean_ayat / max(1, n_ayat), 1),
            "words": n_words,
            "hours": round(dur_ms / 3_600_000, 2),
            "insertions": n_ins, "deletions": n_del, "transpositions": n_trans,
            "word_dur_ms_p50": word_durs[len(word_durs) // 2] if word_durs else None,
            "word_dur_ms_p95": word_durs[int(len(word_durs) * 0.95)] if word_durs else None,
            "word_dur_ms_max": word_durs[-1] if word_durs else None,
        }
        tot_ayat += n_ayat
        tot_words += n_words
        tot_ms += dur_ms

    gap_stats.sort()
    out["totals"] = {
        "reciters_usable": len(out["reciters"]),
        "ayah_recordings": tot_ayat,
        "word_instances": tot_words,
        "hours_total": round(tot_ms / 3_600_000, 2),
        "inter_word_gap_ms_p50": gap_stats[len(gap_stats) // 2] if gap_stats else None,
        "inter_word_gap_ms_p90": gap_stats[int(len(gap_stats) * 0.90)] if gap_stats else None,
    }

    # Held-out accounting: RATL-Bench-v0 must never appear in training.
    held = len(BENCH_AYAT) * len(BENCH_RECITERS)
    out["heldout"] = {
        "bench_ayat": len(BENCH_AYAT),
        "bench_reciters": len(BENCH_RECITERS),
        "bench_clips": held,
        "policy": "RATL-Bench-v0 ayat excluded from ALL training splits, across ALL reciters",
    }

    # What a fully clean training pool looks like after holding out bench ayat.
    clean_pool_ayat = clean_pool_words = 0
    clean_pool_ms = 0.0
    for f in sorted(GT.glob("*.json")):
        if f.name in EXCLUDE:
            continue
        raw = f.read_text(encoding="utf-8")
        if not raw.lstrip().startswith("["):
            continue
        for r in json.loads(raw):
            if (r["surah"], r["ayah"]) in BENCH_AYAT:
                continue
            st = r.get("stats", {})
            if st.get("insertions") or st.get("deletions") or st.get("transpositions"):
                continue
            segs = r.get("segments") or []
            if not segs:
                continue
            clean_pool_ayat += 1
            clean_pool_words += len(segs)
            clean_pool_ms += segs[-1][3] - segs[0][2]

    out["clean_training_pool"] = {
        "definition": "stats.insertions==deletions==transpositions==0, bench ayat removed",
        "ayah_recordings": clean_pool_ayat,
        "word_instances": clean_pool_words,
        "hours": round(clean_pool_ms / 3_600_000, 2),
    }
    return out


if __name__ == "__main__":
    rep = survey()
    (RESULTS / "06_corpus_survey.json").write_text(
        json.dumps(rep, indent=2), encoding="utf-8")
    print(json.dumps(rep["totals"], indent=2))
    print("\nclean training pool:")
    print(json.dumps(rep["clean_training_pool"], indent=2))
    print("\nheld out:")
    print(json.dumps(rep["heldout"], indent=2))
    print("\nper reciter:")
    for k, v in rep["reciters"].items():
        print(f"  {k:<34} {v.get('hours','?'):>7} h  {v.get('words','?'):>7} words  "
              f"clean {v.get('pct_ayat_clean','?')}%  "
              f"wdur p50={v.get('word_dur_ms_p50')} p95={v.get('word_dur_ms_p95')}")
