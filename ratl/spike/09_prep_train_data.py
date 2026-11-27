"""
RATL Rung 3 — Experiment 1 data prep.

Builds a ~6 h continuous-recitation training set from the clean quran-align pool.
RATL-Bench-v0 ayat are excluded across ALL reciters.

Downloads audio only; no training here. THROWAWAY SPIKE CODE.
"""

import json
import pathlib
import random
import urllib.request

HERE = pathlib.Path(__file__).parent
GT = HERE / ".cache" / "quran-align"
AUDIO = HERE / ".cache" / "audio"
TR = HERE / ".cache" / "translit"
OUTDIR = HERE / "corpus"
OUTDIR.mkdir(exist_ok=True)

TARGET_HOURS = 6.0
SEED = 1447

RECITERS = {
    "Husary_64kbps": "measured",
    "Alafasy_128kbps": "moderate",
    "Abdul_Basit_Mujawwad_128kbps": "very_slow",
}

BENCH_AYAT = {
    (1, 1), (1, 2), (1, 5), (1, 7), (2, 1), (2, 5), (2, 255), (3, 8),
    (4, 1), (6, 1), (12, 1), (18, 1), (18, 10), (24, 35), (36, 1), (36, 20),
    (55, 1), (55, 13), (67, 1), (78, 1), (87, 1), (91, 1), (93, 1), (94, 1),
    (96, 1), (97, 1), (103, 1), (108, 1), (112, 1), (114, 1),
}

MAX_CLIP_S = 15.0
MIN_CLIP_S = 1.5


def fetch_translit(s, a):
    p = TR / f"{s:03d}{a:03d}.json"
    if p.exists():
        return json.loads(p.read_text(encoding="utf-8"))
    url = (f"https://api.quran.com/api/v4/verses/by_key/{s}:{a}"
           "?words=true&word_fields=transliteration,text_uthmani")
    req = urllib.request.Request(url, headers={"User-Agent": "RATL-spike/0.1"})
    with urllib.request.urlopen(req, timeout=60) as r:
        data = json.loads(r.read().decode("utf-8"))
    words = [{"pos": w["position"],
              "tr": (w.get("transliteration") or {}).get("text") or ""}
             for w in data["verse"]["words"] if w.get("char_type_name") == "word"]
    p.write_text(json.dumps(words, ensure_ascii=False), encoding="utf-8")
    return words


def fetch_audio(rec, s, a):
    dest = AUDIO / rec / f"{s:03d}{a:03d}.mp3"
    if dest.exists() and dest.stat().st_size > 0:
        return dest
    dest.parent.mkdir(parents=True, exist_ok=True)
    url = f"https://everyayah.com/data/{rec}/{s:03d}{a:03d}.mp3"
    req = urllib.request.Request(url, headers={"User-Agent": "RATL-spike/0.1"})
    with urllib.request.urlopen(req, timeout=90) as r, open(dest, "wb") as f:
        f.write(r.read())
    return dest


def main():
    rng = random.Random(SEED)
    per_reciter_s = TARGET_HOURS * 3600 / len(RECITERS)
    manifest = []
    stats = {}

    for rec, speed in RECITERS.items():
        rows = json.loads((GT / f"{rec.replace('_64kbps','_64kbps').replace('_128kbps','_128kbps')}.json"
                           if False else GT / f"{rec}.json").read_text(encoding="utf-8"))
        cands = []
        for r in rows:
            key = (r["surah"], r["ayah"])
            if key in BENCH_AYAT:
                continue
            st = r.get("stats", {})
            if st.get("insertions") or st.get("deletions") or st.get("transpositions"):
                continue
            segs = r.get("segments") or []
            if not segs:
                continue
            dur = (segs[-1][3] - segs[0][2]) / 1000.0
            if dur < MIN_CLIP_S or dur > MAX_CLIP_S:
                continue
            cands.append((key, segs, dur))
        rng.shuffle(cands)

        got = 0.0
        n = 0
        for (s, a), segs, dur in cands:
            if got >= per_reciter_s:
                break
            try:
                words = fetch_translit(s, a)
                if len(words) != len(segs):
                    continue
                fetch_audio(rec, s, a)
            except Exception:  # noqa: BLE001
                continue
            manifest.append({
                "reciter": rec, "speed": speed, "surah": s, "ayah": a,
                "audio": f"{rec}/{s:03d}{a:03d}.mp3",
                "duration_s": round(dur, 2),
                "words": [w["tr"] for w in words],
                "segments": segs,
            })
            got += dur
            n += 1
            if n % 100 == 0:
                print(f"  {rec:<32} {n:>5} clips  {got/3600:.2f} h")
        stats[rec] = {"clips": n, "hours": round(got / 3600, 2), "speed": speed}
        print(f"[done] {rec:<32} {n} clips  {got/3600:.2f} h")

    out = {"seed": SEED, "target_hours": TARGET_HOURS,
           "heldout_policy": "RATL-Bench-v0 ayat excluded, all reciters",
           "max_clip_s": MAX_CLIP_S, "stats": stats,
           "total_clips": len(manifest),
           "total_hours": round(sum(m["duration_s"] for m in manifest) / 3600, 2),
           "clips": manifest}
    (OUTDIR / "exp1-train.json").write_text(json.dumps(out, ensure_ascii=False), encoding="utf-8")
    print(f"\ntotal: {out['total_clips']} clips  {out['total_hours']} h")
    print(json.dumps(stats, indent=2))


if __name__ == "__main__":
    main()
