"""
RATL M1 - build RATL-Val-v0, a held-out validation set. NO DOWNLOADS.

Why this exists: EXP3_RESULTS.md section 7 flagged that M2_separator was chosen
by comparing four rules on RATL-Bench-v0, i.e. selection on the evaluation set.
Every further decode decision (window overlap, latency offset) must therefore be
made on data the bench never sees, and the bench used ONCE at the end as the gate.

RATL-Val-v0 is built entirely from audio already cached:

  SHORT items - single ayat from the 6 reciters the exp1 checkpoint NEVER
    trained on (it saw only Husary_64kbps, Alafasy_128kbps,
    Abdul_Basit_Mujawwad_128kbps). Unseen voice AND unseen ayah.

  LONG items - consecutive ayat from those same reciters, concatenated into
    30-60 s items with ground-truth times shifted into the concatenated
    timebase. These exercise the long-form windowing path, which short clips
    cannot: every clip in the training corpus is <= 15 s, so the 15 s window
    boundary is never crossed.

DECLARED ARTIFACT: concatenating separate ayah recordings joins them without the
natural breath/pause a continuous recitation would have. Long items therefore
test WINDOW STITCHING, which is what they are for; they are not a claim about
natural long-form audio. RATL-Bench-v0 contains genuinely long single ayat
(e.g. 2:255) and remains the gate.

RATL-Bench-v0 ayat are excluded here exactly as in training.

THROWAWAY SPIKE CODE. Not RATL Core.
"""

import json
import pathlib
import random

HERE = pathlib.Path(__file__).parent
AUDIO = HERE / ".cache" / "audio"
CORPUS = HERE / "corpus"

SEED = 1448                      # distinct from training seed 1447
N_SHORT_PER_RECITER = 70
N_LONG_PER_RECITER = 22
LONG_RUN = 3                     # consecutive ayat per long item

EXP1_TRAINED_RECITERS = {"Husary_64kbps", "Alafasy_128kbps",
                         "Abdul_Basit_Mujawwad_128kbps"}


def main():
    import librosa

    rng = random.Random(SEED)
    d = json.loads((CORPUS / "exp1b-train.json").read_text(encoding="utf-8"))
    pool = [c for c in d["clips"] if c["reciter"] not in EXP1_TRAINED_RECITERS]
    reciters = sorted({c["reciter"] for c in pool})
    print(f"[pool] {len(pool)} clips from {len(reciters)} unseen reciters")

    by = {}
    for c in pool:
        by.setdefault(c["reciter"], {})[(c["surah"], c["ayah"])] = c

    short_items, long_items = [], []

    for rec in reciters:
        keys = sorted(by[rec])
        # ---- long items first, so their ayat can be excluded from short ----
        runs = [k for k in keys
                if all((k[0], k[1] + i) in by[rec] for i in range(LONG_RUN))]
        rng.shuffle(runs)
        used = set()
        picked = 0
        for (s, a) in runs:
            if picked >= N_LONG_PER_RECITER:
                break
            span = [(s, a + i) for i in range(LONG_RUN)]
            if any(k in used for k in span):
                continue
            words, segs, off = [], [], 0.0
            ok = True
            for k in span:
                c = by[rec][k]
                p = AUDIO / c["audio"]
                try:
                    dur = librosa.get_duration(path=str(p)) * 1000.0
                except Exception:  # noqa: BLE001
                    ok = False
                    break
                base = len(words)
                for w in c["words"]:
                    words.append(w)
                for sg in c["segments"]:
                    segs.append([base + sg[0], 0,
                                 round(sg[2] + off, 1), round(sg[3] + off, 1)])
                off += dur
            if not ok:
                continue
            used.update(span)
            long_items.append({
                "id": f"{rec}|{s:03d}{a:03d}+{LONG_RUN}",
                "reciter": rec, "kind": "long", "speed": by[rec][(s, a)]["speed"],
                "parts": [f"{rec}/{k[0]:03d}{k[1]:03d}.mp3" for k in span],
                "duration_s": round(off / 1000.0, 2),
                "words": words, "segments": segs,
            })
            picked += 1

        # ---- short items, disjoint from the long ones ----
        cands = [k for k in keys if k not in used]
        rng.shuffle(cands)
        for k in cands[:N_SHORT_PER_RECITER]:
            c = by[rec][k]
            short_items.append({
                "id": f"{rec}|{k[0]:03d}{k[1]:03d}",
                "reciter": rec, "kind": "short", "speed": c["speed"],
                "parts": [c["audio"]],
                "duration_s": c["duration_s"],
                "words": c["words"],
                "segments": [[sg[0], 0, sg[2], sg[3]] for sg in c["segments"]],
            })
        print(f"  {rec:<32} short {N_SHORT_PER_RECITER}  long {picked}")

    items = short_items + long_items
    out = {
        "name": "RATL-Val-v0",
        "purpose": ("held-out selection set; RATL-Bench-v0 is the gate and must "
                    "not be used to choose decode parameters"),
        "seed": SEED,
        "unseen_wrt": "exp1 checkpoint (trained only on Husary/Alafasy/AB-Mujawwad)",
        "bench_ayat_excluded": True,
        "declared_artifact": ("long items are concatenated separate ayah "
                              "recordings; they test window stitching, not "
                              "natural long-form audio"),
        "n_short": len(short_items), "n_long": len(long_items),
        "hours": round(sum(i["duration_s"] for i in items) / 3600, 3),
        "long_duration_s": {
            "min": round(min(i["duration_s"] for i in long_items), 1),
            "median": round(sorted(i["duration_s"] for i in long_items)[len(long_items) // 2], 1),
            "max": round(max(i["duration_s"] for i in long_items), 1)},
        "items": items,
    }
    (CORPUS / "ratl-val-v0.json").write_text(json.dumps(out, ensure_ascii=False),
                                             encoding="utf-8")
    print(f"\nRATL-Val-v0: {len(short_items)} short + {len(long_items)} long "
          f"= {len(items)} items, {out['hours']} h")
    print(f"  long duration s: {out['long_duration_s']}")
    n_cross = sum(1 for i in long_items if i["duration_s"] > 15)
    print(f"  long items exceeding the 15 s window: {n_cross}/{len(long_items)}")


if __name__ == "__main__":
    main()
