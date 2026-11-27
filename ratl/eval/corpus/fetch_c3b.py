"""C3-b - fetch a FRESH reciter-disjoint evaluation set. User-approved, ~185 MB cap.

Why this download exists: the first reciter-disjoint tier has been observed, so
it is spent as a gate. It is retained as exploratory/diagnostic data only. The
lattice redesign needs a gate sample it has never influenced, and the only
reciters the frozen model never trained on are Hani_Rifai and Minshawy_Murattal,
of which just 30 bench ayat were cached.

Discipline enforced here:
  * HARD byte cap. The run stops at the cap, mid-reciter if necessary.
  * Resumable. Existing, non-empty, size-matching files are never re-fetched.
  * Size-validated against Content-Length; a short read is deleted, not kept.
  * RATL-Bench-v0 ayat are EXCLUDED, so the fresh tier is independent of the G2
    gate corpus as well as of the consumed tier.
  * Candidates are chosen from quran-align ground-truth duration, so clips that
    would be filtered out for exceeding the 15 s window are never downloaded.
"""
import json
import pathlib
import random
import sys
import time
import urllib.request

ROOT = pathlib.Path(__file__).resolve().parent.parent.parent
SPIKE = ROOT / "spike"
AUDIO = SPIKE / ".cache" / "audio"
GT_DIR = SPIKE / ".cache" / "quran-align"
TR_CACHE = SPIKE / ".cache" / "translit"
MANIFEST = ROOT / "eval" / "corpus" / "c3b-manifest.json"

RECITERS = ["Hani_Rifai_192kbps", "Minshawy_Murattal_128kbps"]
BYTE_CAP = 185 * 1024 * 1024
MAX_DUR_S = 15.0
MIN_WORDS = 4
PER_RECITER = 400
SEED = 1451
UA = {"User-Agent": "RATL-eval/1.0"}

# RATL-Bench-v0 ayat - excluded so the fresh tier is independent of the G2 gate
BENCH = {(1, 1), (1, 2), (1, 5), (1, 7), (2, 1), (2, 5), (2, 255), (3, 8),
         (4, 1), (6, 1), (12, 1), (18, 1), (18, 10), (24, 35), (36, 1), (36, 20),
         (55, 1), (55, 13), (67, 1), (78, 1), (87, 1), (91, 1), (93, 1), (94, 1),
         (96, 1), (97, 1), (103, 1), (108, 1), (112, 1), (114, 1)}


def load_gt(rec):
    f = GT_DIR / f"{rec}.json"
    raw = f.read_text(encoding="utf-8")
    if not raw.lstrip().startswith("["):
        return None
    return json.loads(raw)


def candidates(rec, rng):
    rows = load_gt(rec)
    out = []
    for r in rows:
        s, a = r["surah"], r["ayah"]
        if (s, a) in BENCH:
            continue
        segs = [sg for sg in (r.get("segments") or []) if sg[3] > sg[2]]
        if len(segs) < MIN_WORDS:
            continue
        dur = max(sg[3] for sg in segs) / 1000.0
        if dur > MAX_DUR_S or dur < 2.0:
            continue
        out.append((s, a, round(dur, 2), len(segs)))
    rng.shuffle(out)
    return out


def get(url, dest, timeout=90):
    """Fetch with size validation. Returns bytes written, or 0."""
    req = urllib.request.Request(url, headers=UA)
    for attempt in range(3):
        try:
            with urllib.request.urlopen(req, timeout=timeout) as r:
                declared = int(r.headers.get("Content-Length") or 0)
                data = r.read()
            if declared and len(data) != declared:
                raise OSError(f"short read {len(data)}/{declared}")
            if len(data) < 1024:
                raise OSError(f"implausibly small: {len(data)} bytes")
            dest.parent.mkdir(parents=True, exist_ok=True)
            dest.write_bytes(data)
            return len(data)
        except Exception as e:  # noqa: BLE001
            if attempt == 2:
                print(f"        FAILED {url}: {e}", flush=True)
                if dest.exists():
                    dest.unlink()
                return 0
            time.sleep(2 * (attempt + 1))
    return 0


def main():
    rng = random.Random(SEED)
    spent = 0
    got = {r: [] for r in RECITERS}
    t0 = time.time()

    plan = {r: candidates(r, rng)[:PER_RECITER] for r in RECITERS}
    for r in RECITERS:
        print(f"[plan] {r}: {len(plan[r])} candidate ayat", flush=True)

    # interleave the reciters so a cap hit leaves both roughly balanced
    queue = []
    for i in range(max(len(v) for v in plan.values())):
        for r in RECITERS:
            if i < len(plan[r]):
                queue.append((r, *plan[r][i]))

    for rec, s, a, dur, nw in queue:
        if spent >= BYTE_CAP:
            print(f"[cap] byte cap reached at {spent/1048576:.1f} MB", flush=True)
            break
        dest = AUDIO / rec / f"{s:03d}{a:03d}.mp3"
        if dest.exists() and dest.stat().st_size > 1024:
            got[rec].append({"surah": s, "ayah": a, "dur_s": dur, "n_words": nw,
                             "bytes": dest.stat().st_size, "cached": True})
            continue
        url = f"https://everyayah.com/data/{rec}/{s:03d}{a:03d}.mp3"
        n = get(url, dest)
        if n:
            spent += n
            got[rec].append({"surah": s, "ayah": a, "dur_s": dur, "n_words": nw,
                             "bytes": n, "cached": False})
        if (len(got[rec]) % 50) == 0 and n:
            print(f"      {rec:<30} {len(got[rec]):4d} ayat  "
                  f"{spent/1048576:7.1f} MB  ({time.time()-t0:.0f}s)", flush=True)

    # transliterations for exactly what was fetched (small JSON, ~2 KB each)
    tr_bytes = 0
    need = [(s["surah"], s["ayah"]) for r in RECITERS for s in got[r]]
    need = sorted(set(need))
    for (s, a) in need:
        p = TR_CACHE / f"{s:03d}{a:03d}.json"
        if p.exists():
            continue
        url = (f"https://api.quran.com/api/v4/verses/by_key/{s}:{a}"
               "?words=true&word_fields=transliteration,text_uthmani")
        try:
            req = urllib.request.Request(url, headers=UA)
            with urllib.request.urlopen(req, timeout=60) as r:
                data = json.loads(r.read().decode("utf-8"))
            words = [{"pos": w["position"],
                      "tr": (w.get("transliteration") or {}).get("text") or "",
                      "ar": w.get("text_uthmani", "")}
                     for w in data["verse"]["words"]
                     if w.get("char_type_name") == "word"]
            p.write_text(json.dumps(words, ensure_ascii=False), encoding="utf-8")
            tr_bytes += len(json.dumps(words))
        except Exception as e:  # noqa: BLE001
            print(f"        translit failed {s}:{a}: {e}", flush=True)
        time.sleep(0.05)

    man = {"purpose": "C3-b fresh reciter-disjoint gate data",
           "approved_cap_mb": BYTE_CAP / 1048576,
           "audio_bytes_downloaded": spent,
           "audio_mb_downloaded": round(spent / 1048576, 2),
           "translit_bytes_downloaded": tr_bytes,
           "bench_ayat_excluded": True,
           "max_duration_s": MAX_DUR_S, "min_words": MIN_WORDS, "seed": SEED,
           "reciters": {r: {"n_ayat": len(got[r]),
                            "n_newly_downloaded": sum(1 for x in got[r] if not x["cached"]),
                            "total_dur_s": round(sum(x["dur_s"] for x in got[r]), 1),
                            "items": got[r]} for r in RECITERS}}
    MANIFEST.write_text(json.dumps(man, indent=1, ensure_ascii=False), encoding="utf-8")

    print("\n" + "=" * 68)
    print("C3-b FETCH COMPLETE")
    print("=" * 68)
    print(f"  audio downloaded : {man['audio_mb_downloaded']} MB "
          f"(cap {BYTE_CAP/1048576:.0f} MB)")
    print(f"  translit         : {tr_bytes/1024:.0f} KB")
    for r in RECITERS:
        d = man["reciters"][r]
        print(f"  {r:<32} {d['n_ayat']:4d} ayat  "
              f"{d['n_newly_downloaded']:4d} new  {d['total_dur_s']/60:.1f} min")


if __name__ == "__main__":
    main()
