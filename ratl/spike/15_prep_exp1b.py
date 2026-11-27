"""
RATL Rung 3 - Experiment 1b data prep: scale training data 6.0 h -> ~30 h.

Only the DATA changes. Architecture, hyperparameters, evaluation harness and
RATL-Bench-v0 are all unchanged from Experiment 1, so the measured G2 delta is
attributable to data volume/diversity.

RECITER SET - 9 reciters, chosen to avoid confounding the comparison:
  * the SAME 3 bench reciters Experiment 1 already trained on, plus
  * ALL 6 non-bench reciters in the clean pool.
  Hani_Rifai_192kbps and Minshawy_Murattal_128kbps are deliberately LEFT OUT of
  training so bench-reciter coverage is identical to Experiment 1 (3 of 5 seen).
  Without this, "more data" would be confounded with "more of the bench voices",
  and any G2 gain would be uninterpretable. It also yields a free seen-voice vs
  unseen-voice generalisation readout on the frozen bench.

RATL-Bench-v0 ayat remain excluded across ALL reciters.
SEED unchanged (1447), so Experiment 1 clips remain a prefix of the shuffled
candidate order for the 3 shared reciters -- 1b is a superset, not a resample.

THROWAWAY SPIKE CODE. Not RATL Core.
"""

import json
import pathlib
import random
import sys
import time
import urllib.request
from concurrent.futures import ThreadPoolExecutor, as_completed

HERE = pathlib.Path(__file__).parent
GT = HERE / ".cache" / "quran-align"
AUDIO = HERE / ".cache" / "audio"
TR = HERE / ".cache" / "translit"
OUTDIR = HERE / "corpus"
OUTDIR.mkdir(exist_ok=True)
TR.mkdir(parents=True, exist_ok=True)

TARGET_HOURS = 30.0
SEED = 1447                  # unchanged from Experiment 1
MAX_CLIP_S = 15.0            # unchanged - matches the 15 s encoder window
MIN_CLIP_S = 1.5             # unchanged

# weight 1.5 on Mujawwad: it is the measured weakest class (G2 0.4684)
RECITERS = {
    # --- seen in Experiment 1 (3 of the 5 bench reciters) ---
    "Husary_64kbps":                 ("measured",  1.0, True),
    "Alafasy_128kbps":               ("moderate",  1.0, True),
    "Abdul_Basit_Mujawwad_128kbps":  ("very_slow", 1.5, True),
    # --- new in Experiment 1b (all 6 non-bench reciters) ---
    "Abdul_Basit_Murattal_64kbps":   ("medium",    1.0, False),
    "Abu_Bakr_Ash-Shaatree_128kbps": ("medium",    1.0, False),
    "Husary_Muallim_128kbps":        ("teaching",  1.0, False),
    "Minshawy_Mujawwad_192kbps":     ("very_slow", 1.5, False),
    "Mohammad_al_Tablaway_128kbps":  ("moderate",  1.0, False),
    "Saood_ash-Shuraym_128kbps":     ("very_fast", 1.0, False),
}

# held out of training entirely - unseen voices on the frozen bench
UNSEEN_BENCH_RECITERS = ["Hani_Rifai_192kbps", "Minshawy_Murattal_128kbps"]

BENCH_AYAT = {
    (1, 1), (1, 2), (1, 5), (1, 7), (2, 1), (2, 5), (2, 255), (3, 8),
    (4, 1), (6, 1), (12, 1), (18, 1), (18, 10), (24, 35), (36, 1), (36, 20),
    (55, 1), (55, 13), (67, 1), (78, 1), (87, 1), (91, 1), (93, 1), (94, 1),
    (96, 1), (97, 1), (103, 1), (108, 1), (112, 1), (114, 1),
}

UA = {"User-Agent": "RATL-spike/0.1"}

# Metered-connection tuning. The bundle is 3 GB per 3 HOURS, so wall-clock is as
# scarce as bytes: fail fast and retry rather than blocking on a dead socket for
# minutes, and keep enough sockets busy to saturate a weak link.
TR_WORKERS, TR_TIMEOUT = 12, 15
AU_WORKERS, AU_TIMEOUT = 12, 45


def fetch_translit(key):
    s, a = key
    p = TR / f"{s:03d}{a:03d}.json"
    if p.exists():
        try:
            return key, json.loads(p.read_text(encoding="utf-8"))
        except Exception:  # noqa: BLE001
            p.unlink(missing_ok=True)
    url = (f"https://api.quran.com/api/v4/verses/by_key/{s}:{a}"
           "?words=true&word_fields=transliteration,text_uthmani")
    for attempt in range(4):
        try:
            req = urllib.request.Request(url, headers=UA)
            with urllib.request.urlopen(req, timeout=TR_TIMEOUT) as r:
                data = json.loads(r.read().decode("utf-8"))
            words = [{"pos": w["position"],
                      "tr": (w.get("transliteration") or {}).get("text") or ""}
                     for w in data["verse"]["words"] if w.get("char_type_name") == "word"]
            p.write_text(json.dumps(words, ensure_ascii=False), encoding="utf-8")
            return key, words
        except Exception:  # noqa: BLE001
            if attempt == 3:
                return key, None
    return key, None


def fetch_audio(job):
    """Fetch one ayah mp3. Returns (job, ok, bytes_downloaded).

    Writes via a .part file so an interrupted transfer can never leave a
    truncated mp3 that a later run would mistake for a complete one.
    """
    rec, s, a = job
    dest = AUDIO / rec / f"{s:03d}{a:03d}.mp3"
    if dest.exists() and dest.stat().st_size > 2048:
        return job, True, 0
    dest.parent.mkdir(parents=True, exist_ok=True)
    part = dest.with_suffix(".part")
    url = f"https://everyayah.com/data/{rec}/{s:03d}{a:03d}.mp3"
    for attempt in range(3):
        try:
            req = urllib.request.Request(url, headers=UA)
            with urllib.request.urlopen(req, timeout=AU_TIMEOUT) as r:
                blob = r.read()
            if len(blob) < 2048:
                raise OSError("truncated response")
            part.write_bytes(blob)
            part.replace(dest)
            return job, True, len(blob)
        except Exception:  # noqa: BLE001
            part.unlink(missing_ok=True)
            if attempt == 2:
                return job, False, 0
    return job, False, 0


def ok_audio(p):
    """A usable mp3: present AND large enough to be a real clip, not a stub."""
    return p.exists() and p.stat().st_size > 2048


def write_manifest(manifest, avail, audio_complete):
    """Write corpus/exp1b-train.json. Called before AND after the audio phase."""
    stats = {}
    for rec, (speed, _w, seen) in RECITERS.items():
        rows = [m for m in manifest if m["reciter"] == rec]
        stats[rec] = {"clips": len(rows), "speed": speed, "seen_in_exp1": seen,
                      "hours": round(sum(r["duration_s"] for r in rows) / 3600, 2),
                      "pool_hours_available": round(avail[rec], 2)}
    out = {
        "experiment": "1b",
        "seed": SEED, "target_hours": TARGET_HOURS,
        "audio_complete": audio_complete,
        "changed_vs_exp1": "training data only (6.0 h / 3 reciters -> ~30 h / 9 reciters)",
        "unchanged_vs_exp1": ["architecture", "hyperparameters", "vocab construction",
                              "evaluation harness (05_g2_alignment.py)",
                              "RATL-Bench-v0", "SPEC thresholds"],
        "heldout_policy": "RATL-Bench-v0 ayat excluded from ALL reciters",
        "unseen_bench_reciters": UNSEEN_BENCH_RECITERS,
        "unseen_rationale": ("bench-reciter coverage held identical to Experiment 1 "
                             "(3 of 5 seen) so the G2 delta is not confounded"),
        "max_clip_s": MAX_CLIP_S, "min_clip_s": MIN_CLIP_S,
        "stats": stats,
        "total_clips": len(manifest),
        "total_hours": round(sum(m["duration_s"] for m in manifest) / 3600, 2),
        "clips": manifest,
    }
    (OUTDIR / "exp1b-train.json").write_text(json.dumps(out, ensure_ascii=False),
                                             encoding="utf-8")
    return out


def candidates(rec, rng):
    """Clean, in-window, non-bench ayat for one reciter, in stable shuffled order."""
    rows = json.loads((GT / f"{rec}.json").read_text(encoding="utf-8"))
    out = []
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
        out.append((key, segs, dur))
    rng.shuffle(out)
    return out


def main():
    rng = random.Random(SEED)
    pools, avail = {}, {}
    for rec in RECITERS:
        pools[rec] = candidates(rec, rng)
        avail[rec] = sum(c[2] for c in pools[rec]) / 3600.0
        print(f"  pool {rec:<32} {len(pools[rec]):>5} clips  {avail[rec]:>6.2f} h available")

    # --- allocation: weighted share, then redistribute any shortfall ---
    wsum = sum(w for _, w, _ in RECITERS.values())
    want = {r: TARGET_HOURS * w / wsum for r, (_, w, _) in RECITERS.items()}
    deficit = sum(max(0.0, want[r] - avail[r]) for r in want)
    for r in want:
        want[r] = min(want[r], avail[r])
    if deficit > 0:
        headroom = {r: max(0.0, avail[r] - want[r]) for r in want}
        htot = sum(headroom.values())
        print(f"\n  [alloc] deficit {deficit:.2f} h redistributed over {htot:.2f} h headroom")
        for r in want:
            if htot > 0:
                want[r] += deficit * headroom[r] / htot
            want[r] = min(want[r], avail[r])

    # --- provisional selection, to bound how many transliterations we fetch ---
    keys = set()
    for rec in RECITERS:
        need_s, got = want[rec] * 3600, 0.0
        for key, _segs, dur in pools[rec]:
            if got >= need_s * 1.15:   # 15% slack for word-count mismatches
                break
            keys.add(key)
            got += dur
    keys = sorted(keys)
    missing = [k for k in keys if not (TR / f"{k[0]:03d}{k[1]:03d}.json").exists()]
    print(f"\n  [translit] {len(keys)} distinct ayat, {len(missing)} to fetch")

    translit = {}
    with ThreadPoolExecutor(max_workers=TR_WORKERS) as ex:
        futs = [ex.submit(fetch_translit, k) for k in keys]
        for n, f in enumerate(as_completed(futs), 1):
            k, w = f.result()
            if w:
                translit[k] = w
            if n % 500 == 0:
                print(f"        translit {n}/{len(keys)}", flush=True)

    # --- final selection: word-count agreement filter, walking the same order ---
    manifest, audio_jobs = [], []
    for rec, (speed, _w, seen) in RECITERS.items():
        need_s, got, n_drop = want[rec] * 3600, 0.0, 0
        chosen = []
        for key, segs, dur in pools[rec]:
            if got >= need_s:
                break
            w = translit.get(key)
            if w is None:
                _, w = fetch_translit(key)
                if w:
                    translit[key] = w
            if not w or len(w) != len(segs):
                n_drop += 1
                continue
            chosen.append((key, segs, dur, w))
            got += dur
        for (s, a), segs, dur, w in chosen:
            manifest.append({
                "reciter": rec, "speed": speed, "seen_in_exp1": seen,
                "surah": s, "ayah": a,
                "audio": f"{rec}/{s:03d}{a:03d}.mp3",
                "duration_s": round(dur, 2),
                "words": [x["tr"] for x in w],
                "segments": segs,
            })
            audio_jobs.append((rec, s, a))
        print(f"  select {rec:<32} {len(chosen):>5} clips  {got/3600:>5.2f} h "
              f"(dropped {n_drop} on word-count mismatch)")

    # --- persist the manifest BEFORE spending the metered bundle on audio ---
    # Selection is pure CPU over cached ground truth. Writing it now means an
    # interrupted download never costs us the selection work, and the audio
    # phase becomes a resumable, restartable step of its own.
    write_manifest(manifest, avail, audio_complete=False)
    print(f"  [manifest] {len(manifest)} clips written before download (resumable)",
          flush=True)

    # NOTE: size check, not .exists(). The Experiment 1 prep script wrote
    # downloads without validating length, leaving a 0-byte mp3 that .exists()
    # happily reports as present; it crashed feature extraction days later.
    todo = [j for j in audio_jobs if not ok_audio(AUDIO / j[0] / f"{j[1]:03d}{j[2]:03d}.mp3")]
    est_mb = sum(m["duration_s"] for m in manifest
                 if (m["reciter"], m["surah"], m["ayah"]) in set(todo)) / 3600 * 62
    print(f"\n  [audio] {len(audio_jobs)} clips, {len(todo)} to download, "
          f"~{est_mb:.0f} MB estimated", flush=True)

    failed = set()
    t0 = time.time()
    got_b = 0
    with ThreadPoolExecutor(max_workers=AU_WORKERS) as ex:
        futs = [ex.submit(fetch_audio, j) for j in todo]
        try:
            for n, f in enumerate(as_completed(futs), 1):
                job, ok, nb = f.result()
                got_b += nb
                if not ok:
                    failed.add(job)
                if n % 100 == 0 or n == len(todo):
                    el = max(1e-6, time.time() - t0)
                    rate = got_b / el / 1024
                    eta = (len(todo) - n) * (el / n) / 60
                    print(f"        audio {n}/{len(todo)}  "
                          f"{got_b/1e6:7.1f} MB  {rate:6.1f} KB/s  "
                          f"eta {eta:5.1f} min  failed={len(failed)}", flush=True)
        except KeyboardInterrupt:
            # Ctrl-C mid-bundle: keep everything already fetched, stop cleanly.
            print("\n  [audio] interrupted - keeping what was downloaded", flush=True)
            for fu in futs:
                fu.cancel()

    print(f"  [audio] {got_b/1e6:.1f} MB in {(time.time()-t0)/60:.1f} min "
          f"({got_b/max(1e-6, time.time()-t0)/1024:.1f} KB/s avg)", flush=True)

    # Only clips whose audio is actually on disk survive into the final manifest.
    before = len(manifest)
    manifest = [m for m in manifest if ok_audio(AUDIO / m["audio"])]
    if before != len(manifest):
        print(f"  [audio] {before - len(manifest)} clips have no audio yet "
              f"(rerun to resume); manifest trimmed to what is usable", flush=True)

    out = write_manifest(manifest, avail, audio_complete=len(failed) == 0)

    print("\n===== EXP 1b CORPUS =====")
    print(f"  clips {out['total_clips']}   hours {out['total_hours']}   "
          f"audio_complete={out['audio_complete']}")
    for r, v in out["stats"].items():
        print(f"    {r:<32} {v['clips']:>5} clips {v['hours']:>6.2f} h  "
              f"{v['speed']:<10} seen_exp1={v['seen_in_exp1']}")
    if not out["audio_complete"]:
        print("\n  NOTE: audio incomplete. Rerun this script to resume; every "
              "already-fetched file is skipped.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
