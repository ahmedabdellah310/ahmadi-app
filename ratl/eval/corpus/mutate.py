"""RATL-WordErr-v1 / RATL-Clean-v1 - seeded, reproducible mutation corpus.

SPEC 3.2: mutations are applied at known word positions in segmented audio, so
ground truth is EXACT BY CONSTRUCTION - the expected status and index follow from
the edit itself, with no annotation and no listening.

Operators (SPEC 3.2), one expected status each:

    delete      excise the word span                        -> OMISSION
    substitute  splice a different Quranic word over it     -> SUBSTITUTION
    repeat      duplicate the word immediately after itself -> REPETITION
    insert      splice a foreign word between i and i+1     -> INSERTION
    truncate    cut word i at 40-60% and end the audio      -> INCOMPLETE
    null        CONTROL: crossfade at the word boundary and -> CORRECT
                change nothing else

The null operator is the seam control. Every other operator leaves a splice
discontinuity, so without it a detector that merely hears the 10 ms crossfade
would look like a working error detector. null carries the same seam and no
error, so any status other than CORRECT on a null item is a false positive
caused by the splice rather than by the mutation.

Splices are same-reciter, so channel, level and voice are held constant and the
detector is tested on content rather than on an acoustic seam.

Every item is regenerable from (seed, op, reciter, surah, ayah, word_index).
Nothing is downloaded: audio, ground truth and transliterations all come from
the existing cache.
"""
import hashlib
import importlib.util
import json
import pathlib
import random
import sys

import numpy as np

ROOT = pathlib.Path(__file__).resolve().parent.parent.parent
SPIKE = ROOT / "spike"
sys.path.insert(0, str(SPIKE))


def _load(name, fn, base=SPIKE):
    sp = importlib.util.spec_from_file_location(name, base / fn)
    m = importlib.util.module_from_spec(sp)
    sys.modules[name] = m
    sp.loader.exec_module(m)
    return m


g2 = _load("g2", "05_g2_alignment.py")

SR = 16000
CROSSFADE_MS = 10.0
TR_CACHE = SPIKE / ".cache" / "translit"
AUDIO = SPIKE / ".cache" / "audio"
GT_DIR = SPIKE / ".cache" / "quran-align"

SEEN_RECITERS = ["Abdul_Basit_Murattal_64kbps", "Abu_Bakr_Ash-Shaatree_128kbps",
                 "Alafasy_128kbps", "Husary_64kbps", "Husary_Muallim_128kbps",
                 "Minshawy_Mujawwad_192kbps", "Mohammad_al_Tablaway_128kbps",
                 "Saood_ash-Shuraym_128kbps"]
UNSEEN_RECITERS = ["Hani_Rifai_192kbps", "Minshawy_Murattal_128kbps"]

# Truncation is split by CUT POSITION and the word list is stratified by word
# duration, because a word cut at 25% and a word cut at 75% are different
# detection problems and short/long words behave differently (accuracy is known
# to degrade monotonically with word duration).
TRUNC_FRACS = {"truncate_25": (0.20, 0.30), "truncate_50": (0.45, 0.55),
               "truncate_75": (0.70, 0.80)}
OPS = ["delete", "substitute", "repeat", "insert",
       "truncate_25", "truncate_50", "truncate_75", "null"]
EXPECTED = {"delete": "OMISSION", "substitute": "SUBSTITUTION",
            "repeat": "REPETITION", "insert": "INSERTION",
            "truncate_25": "INCOMPLETE", "truncate_50": "INCOMPLETE",
            "truncate_75": "INCOMPLETE", "null": "CORRECT", "clean": "CORRECT"}
SHORT_MS, LONG_MS = 800, 1500


def translit_offline(s, a):
    p = TR_CACHE / f"{s:03d}{a:03d}.json"
    return json.loads(p.read_text(encoding="utf-8")) if p.exists() else None


def load_gt(rec):
    f = GT_DIR / f"{rec}.json"
    if not f.exists():
        return None
    raw = f.read_text(encoding="utf-8")
    if not raw.lstrip().startswith("["):
        return None          # corrupt upstream (Sudais) - excluded per SPEC 3.1
    return {(r["surah"], r["ayah"]): r for r in json.loads(raw)}


def _xfade(head, tail):
    n = int(CROSSFADE_MS / 1000 * SR)
    n = min(n, len(head), len(tail))
    if n <= 0:
        return np.concatenate([head, tail])
    r = np.linspace(0.0, 1.0, n, dtype=np.float32)
    return np.concatenate([head[:-n], head[-n:] * (1 - r) + tail[:n] * r, tail[n:]])


def _ms(x):
    return int(x / 1000 * SR)


def apply_op(op, audio, segs, wi, rng, donor=None):
    """Return (mutated_audio, meta) or None. segs[k] = (idx, ?, start_ms, end_ms)."""
    sg = segs[wi]
    i0, i1 = _ms(sg[2]), _ms(sg[3])
    i0, i1 = max(0, i0), min(len(audio), i1)
    if i1 <= i0:
        return None
    head, body, tail = audio[:i0], audio[i0:i1], audio[i1:]

    if op == "delete":
        if len(head) == 0 or len(tail) == 0:
            return None
        return _xfade(head, tail), {}
    if op == "repeat":
        return _xfade(_xfade(head, body), _xfade(body, tail)), {
            "repeated_ms": round((i1 - i0) / SR * 1000, 1)}
    if op in TRUNC_FRACS:
        lo_f, hi_f = TRUNC_FRACS[op]
        frac = rng.uniform(lo_f, hi_f)
        cut = i0 + int((i1 - i0) * frac)
        if cut <= i0:
            return None
        return audio[:cut], {"truncated_at_frac": round(frac, 3),
                             "cut_band": op,
                             "word_len_class": ("short" if (sg[3] - sg[2]) < SHORT_MS
                                                else "long" if (sg[3] - sg[2]) > LONG_MS
                                                else "mid")}
    if op == "null":
        # identical content, same seam arithmetic as delete/repeat
        return _xfade(_xfade(head, body), tail), {}
    if op in ("substitute", "insert"):
        if donor is None:
            return None
        d_audio, d_seg, d_word, d_key = donor
        j0, j1 = _ms(d_seg[2]), _ms(d_seg[3])
        j0, j1 = max(0, j0), min(len(d_audio), j1)
        if j1 <= j0:
            return None
        chunk = d_audio[j0:j1]
        meta = {"donor": d_key, "donor_word": d_word,
                "donor_ms": round((j1 - j0) / SR * 1000, 1)}
        if op == "substitute":
            return _xfade(_xfade(head, chunk), tail), meta
        return _xfade(_xfade(_xfade(head, body), chunk), tail), meta
    raise ValueError(op)


def pick_donor(rng, rec, gtrows, avoid, cached_audio):
    """A different word from the SAME reciter - channel and voice held constant."""
    keys = [k for k in cached_audio if k != avoid]
    if not keys:
        return None
    rng.shuffle(keys)
    for (s, a) in keys[:40]:
        r = gtrows.get((s, a))
        if not r:
            continue
        segs = [sg for sg in (r.get("segments") or []) if sg[3] > sg[2]]
        if len(segs) < 3:
            continue
        w = translit_offline(s, a)
        if not w:
            continue
        p = AUDIO / rec / f"{s:03d}{a:03d}.mp3"
        if not p.exists():
            continue
        sg = segs[rng.randrange(len(segs))]
        if sg[0] >= len(w):
            continue
        import librosa
        d, _ = librosa.load(str(p), sr=SR, mono=True)
        return d, sg, (w[sg[0]].get("tr") or ""), f"{s}:{a}:{sg[0]}"
    return None


def build_tier(tier, reciters, bench_only, per_op, seed, max_clip_s=15.0):
    import librosa
    rng = random.Random(seed)
    bench = set(g2.AYAT)
    cached_tr = {(int(p.stem[:3]), int(p.stem[3:])) for p in TR_CACHE.glob("*.json")}

    pool = []
    avail = {}
    for rec in reciters:
        rows = load_gt(rec)
        if not rows:
            continue
        adir = AUDIO / rec
        if not adir.is_dir():
            continue
        have = {(int(f.stem[:3]), int(f.stem[3:])) for f in adir.glob("*.mp3")}
        avail[rec] = (rows, sorted(have & cached_tr))
        for (s, a) in have & cached_tr:
            if bench_only != ((s, a) in bench):
                continue
            r = rows.get((s, a))
            if not r:
                continue
            segs = [sg for sg in (r.get("segments") or []) if sg[3] > sg[2]]
            if len(segs) < 4:
                continue
            pool.append((rec, s, a, segs))
    rng.shuffle(pool)

    items, idx = [], 0
    for op in OPS:
        made = 0
        while made < per_op and idx < len(pool) * 8:
            rec, s, a, segs = pool[idx % len(pool)]
            idx += 1
            p = AUDIO / rec / f"{s:03d}{a:03d}.mp3"
            try:
                audio, _ = librosa.load(str(p), sr=SR, mono=True)
            except Exception:  # noqa: BLE001
                continue
            if len(audio) / SR > max_clip_s:
                continue
            words = translit_offline(s, a)
            if not words:
                continue
            cand = [k for k, sg in enumerate(segs) if sg[0] < len(words)]
            if len(cand) < 3:
                continue
            if op in TRUNC_FRACS:
                want_long = (made % 2 == 1)
                pref = [k for k in cand
                        if ((segs[k][3] - segs[k][2]) > LONG_MS) == want_long]
                wi = (pref if pref else cand)[rng.randrange(len(pref) if pref else len(cand))]
            else:
                wi = cand[rng.randrange(len(cand))]
            donor = None
            if op in ("substitute", "insert"):
                donor = pick_donor(rng, rec, avail[rec][0], (s, a), avail[rec][1])
                if donor is None:
                    continue
            out = apply_op(op, audio, segs, wi, rng, donor)
            if out is None:
                continue
            mut, meta = out
            if len(mut) < SR // 2:
                continue
            items.append({
                "id": hashlib.sha256(
                    f"{seed}|{tier}|{op}|{rec}|{s}|{a}|{segs[wi][0]}".encode()
                ).hexdigest()[:16],
                "tier": tier, "op": op, "expected_status": EXPECTED[op],
                "reciter": rec, "surah": s, "ayah": a,
                "word_index": int(segs[wi][0]),
                "word_dur_ms": int(segs[wi][3] - segs[wi][2]),
                "n_words": len(words), "clip_key": f"{rec}|{s}:{a}",
                "orig_dur_ms": round(len(audio) / SR * 1000, 1),
                "mut_dur_ms": round(len(mut) / SR * 1000, 1),
                "meta": meta,
            })
            made += 1
    return items


def render(item):
    """Regenerate one item's audio from its record. Deterministic."""
    import librosa
    rng = random.Random(int(item["id"][:8], 16))
    rows = load_gt(item["reciter"])
    segs = [sg for sg in rows[(item["surah"], item["ayah"])]["segments"] if sg[3] > sg[2]]
    wi = next(k for k, sg in enumerate(segs) if sg[0] == item["word_index"])
    p = AUDIO / item["reciter"] / f"{item['surah']:03d}{item['ayah']:03d}.mp3"
    audio, _ = librosa.load(str(p), sr=SR, mono=True)
    if item["op"] == "clean":
        return audio, segs, wi
    donor = None
    if item["op"] in ("substitute", "insert"):
        d = item["meta"]["donor"]
        ds, da, dw = (int(x) for x in d.split(":"))
        dp = AUDIO / item["reciter"] / f"{ds:03d}{da:03d}.mp3"
        d_audio, _ = librosa.load(str(dp), sr=SR, mono=True)
        d_rows = load_gt(item["reciter"])[(ds, da)]["segments"]
        d_seg = next(sg for sg in d_rows if sg[0] == dw)
        donor = (d_audio, d_seg, item["meta"]["donor_word"], d)
    if item["op"] in TRUNC_FRACS:
        frac = item["meta"]["truncated_at_frac"]
        i0, i1 = _ms(segs[wi][2]), _ms(segs[wi][3])
        return audio[:i0 + int((i1 - i0) * frac)], segs, wi
    out = apply_op(item["op"], audio, segs, wi, rng, donor)
    return (out[0] if out else None), segs, wi
