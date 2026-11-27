"""
RATL Week 1 spike — Day 3: RATL-Bench-v0 assembly + G2 forced-alignment test.

G2 (SPEC.md v0.2 section 7.2): word-boundary accuracy on continuous recitation
>= 85% within +/-150 ms.  BLOCKING, DECISIVE.

Purpose: determine whether the model's FRAME-LEVEL POSTERIORS are good enough for
forced alignment, despite poor FREE DECODING (see DAY2_NOTES.md section 3).

Ground truth : cpfair/quran-align (CC BY 4.0) word timings.
Audio        : everyayah.com per-ayah mp3 (origin of tarteel-ai/everyayah, CC BY 4.0).
Transliteration: quran.com API v4 word-level (same convention as QUL wbw).
                 NOTE: no Buraaq-derived data is used anywhere here.

THROWAWAY SPIKE CODE. Not RATL Core. See WEEK1_PLAN.md section 2.
"""

import json
import os
import pathlib
import time
import urllib.request

HERE = pathlib.Path(__file__).parent
os.environ.setdefault("HF_HOME", str(HERE / ".cache"))

import numpy as np  # noqa: E402

CACHE = HERE / ".cache"
GT_DIR = CACHE / "quran-align"
AUDIO_CACHE = CACHE / "audio"
TR_CACHE = CACHE / "translit"
RESULTS = HERE / "results"
CORPUS = HERE / "corpus"
for p in (AUDIO_CACHE, TR_CACHE, RESULTS, CORPUS):
    p.mkdir(parents=True, exist_ok=True)

INT8_PATH = HERE / "models" / "w2v2-quran-int8.onnx"
SR = 16000
BLANK = 37
G2_TOLERANCE_MS = 150.0
G2_THRESHOLD = 0.85

# reciter folder -> (quran-align file, speed class)
# NOTE (ground-truth defect, SPEC section 3.1 rule 3 -- record, do not silently fix):
# Abdurrahmaan_As-Sudais_192kbps.json in the upstream CC BY 4.0 release is
# corrupt: ~154 KB of alignment-tool crash logs are prepended before the JSON
# array ("Crashed Command '['./align', ...").  The JSON is recoverable by slicing
# from offset 154139, but the crash means that reciter's timings are the output
# of a partially failed run.  EXCLUDED.  Hani_Rifai_192kbps substituted as the
# fast-tempo reciter.  Defect reported upstream-worthy.
RECITERS = {
    "Husary_64kbps": ("Husary_64kbps.json", "measured"),
    "Alafasy_128kbps": ("Alafasy_128kbps.json", "moderate"),
    "Hani_Rifai_192kbps": ("Hani_Rifai_192kbps.json", "fast"),
    "Abdul_Basit_Mujawwad_128kbps": ("Abdul_Basit_Mujawwad_128kbps.json", "very_slow"),
    "Minshawy_Murattal_128kbps": ("Minshawy_Murattal_128kbps.json", "medium"),
}

# 30 ayat: length spread, phonetic spread, deliberate q- and h-dot coverage.
AYAT = [
    (1, 1), (1, 2), (1, 5), (1, 7), (2, 1), (2, 5), (2, 255), (3, 8),
    (4, 1), (6, 1), (12, 1), (18, 1), (18, 10), (24, 35), (36, 1), (36, 20),
    (55, 1), (55, 13), (67, 1), (78, 1), (87, 1), (91, 1), (93, 1), (94, 1),
    (96, 1), (97, 1), (103, 1), (108, 1), (112, 1), (114, 1),
]


# ---------------------------------------------------------------- data fetch
def fetch_audio(reciter: str, s: int, a: int):
    dest = AUDIO_CACHE / reciter / f"{s:03d}{a:03d}.mp3"
    if dest.exists() and dest.stat().st_size > 0:
        return dest
    dest.parent.mkdir(parents=True, exist_ok=True)
    url = f"https://everyayah.com/data/{reciter}/{s:03d}{a:03d}.mp3"
    req = urllib.request.Request(url, headers={"User-Agent": "RATL-spike/0.1"})
    with urllib.request.urlopen(req, timeout=90) as r, open(dest, "wb") as f:
        f.write(r.read())
    return dest


def fetch_translit(s: int, a: int):
    """Word-level transliteration from quran.com API v4. Cached."""
    p = TR_CACHE / f"{s:03d}{a:03d}.json"
    if p.exists():
        return json.loads(p.read_text(encoding="utf-8"))
    url = (
        f"https://api.quran.com/api/v4/verses/by_key/{s}:{a}"
        "?words=true&word_fields=transliteration,text_uthmani"
    )
    req = urllib.request.Request(url, headers={"User-Agent": "RATL-spike/0.1"})
    for attempt in range(4):
        try:
            with urllib.request.urlopen(req, timeout=60) as r:
                data = json.loads(r.read().decode("utf-8"))
            break
        except Exception:  # noqa: BLE001
            if attempt == 3:
                raise
            time.sleep(2 * (attempt + 1))
    words = [
        {"pos": w["position"], "tr": (w.get("transliteration") or {}).get("text") or "",
         "ar": w.get("text_uthmani", "")}
        for w in data["verse"]["words"]
        if w.get("char_type_name") == "word"
    ]
    p.write_text(json.dumps(words, ensure_ascii=False), encoding="utf-8")
    return words


def load_gt():
    """Load ground truth, validating each file. Corrupt files are reported, not patched."""
    gt = {}
    for rec, (fn, _) in RECITERS.items():
        raw = (GT_DIR / fn).read_text(encoding="utf-8")
        if not raw.lstrip().startswith("["):
            raise SystemExit(
                f"GROUND-TRUTH DEFECT: {fn} does not begin with a JSON array. "
                f"First 80 chars: {raw[:80]!r}. Excluded per SPEC 3.1."
            )
        rows = json.loads(raw)
        gt[rec] = {(r["surah"], r["ayah"]): r for r in rows}
        print(f"      ground truth {rec:<32} {len(rows)} ayat")
    return gt


# ------------------------------------------------------------- tokenization
def build_tokens(words, vocab):
    """Concatenate word transliterations into one token id sequence.

    No '|' or space is inserted: DAY2_NOTES section 3 established the model never
    emits them, so forcing them would create spurious alignment states.
    Returns (ids, word_spans, oov_chars) where word_spans[i]=(tok_start,tok_end).
    """
    ids, spans, oov = [], [], []
    for w in words:
        tr = (w["tr"] or "").strip().lower()
        if not tr:
            spans.append(None)
            continue
        start = len(ids)
        for ch in tr:
            if ch in vocab:
                ids.append(vocab[ch])
            else:
                oov.append(ch)
                ids.append(vocab.get("[UNK]", 36))
        spans.append((start, len(ids)))
    return ids, spans, oov


# --------------------------------------------------------- forced alignment
def ctc_forced_align(logp: np.ndarray, labels: list[int], blank: int = BLANK):
    """Viterbi forced alignment over the CTC lattice.

    logp: (T, V) log posteriors. labels: (L,) target ids.
    Returns per-label (start_frame, end_frame), inclusive.
    """
    T = logp.shape[0]
    L = len(labels)
    S = 2 * L + 1
    ext = np.full(S, blank, dtype=np.int64)
    ext[1::2] = labels

    NEG = -1e30
    alpha = np.full(S, NEG, dtype=np.float64)
    alpha[0] = logp[0, ext[0]]
    if S > 1:
        alpha[1] = logp[0, ext[1]]
    back = np.zeros((T, S), dtype=np.int8)  # 0=stay 1=from s-1 2=from s-2

    # skip allowed into s when ext[s] is a real label and != ext[s-2]
    can_skip = np.zeros(S, dtype=bool)
    if S > 2:
        idx = np.arange(2, S)
        can_skip[2:] = (ext[2:] != blank) & (ext[2:] != ext[:-2])
        del idx

    for t in range(1, T):
        a0 = alpha
        a1 = np.concatenate(([NEG], alpha[:-1]))
        a2 = np.concatenate(([NEG, NEG], alpha[:-2]))
        a2 = np.where(can_skip, a2, NEG)
        stack = np.vstack([a0, a1, a2])
        arg = stack.argmax(axis=0)
        best = stack[arg, np.arange(S)]
        back[t] = arg.astype(np.int8)
        alpha = best + logp[t, ext]

    # terminate in last blank or last label
    s = S - 1 if alpha[S - 1] >= alpha[S - 2] else S - 2
    path = np.zeros(T, dtype=np.int64)
    for t in range(T - 1, -1, -1):
        path[t] = s
        s = s - int(back[t, s])

    spans = []
    for l in range(L):
        st = 2 * l + 1
        occ = np.nonzero(path == st)[0]
        spans.append((int(occ[0]), int(occ[-1])) if occ.size else None)
    return spans


def log_softmax(z: np.ndarray) -> np.ndarray:
    z = z - z.max(axis=-1, keepdims=True)
    return z - np.log(np.exp(z).sum(axis=-1, keepdims=True))


# ------------------------------------------------------------------- driver
def main() -> None:
    import librosa
    import onnxruntime as ort
    from transformers import Wav2Vec2Processor

    proc = Wav2Vec2Processor.from_pretrained("TBOGamer22/wav2vec2-quran-phonetics")
    vocab = proc.tokenizer.get_vocab()

    so = ort.SessionOptions()
    so.intra_op_num_threads = 1
    sess = ort.InferenceSession(str(INT8_PATH), so, providers=["CPUExecutionProvider"])

    gt = load_gt()

    print("[1/3] fetching transliterations ...")
    translit = {}
    for s, a in AYAT:
        translit[(s, a)] = fetch_translit(s, a)
    print(f"      {len(translit)} ayat")

    print("[2/3] assembling RATL-Bench-v0 + running alignment ...")
    manifest, per_word, per_clip, oov_all = [], [], [], []
    t_start = time.time()

    for rec, (_, speed) in RECITERS.items():
        for s, a in AYAT:
            g = gt[rec].get((s, a))
            if not g:
                print(f"      SKIP {rec} {s}:{a} (no ground truth)")
                continue
            try:
                ap = fetch_audio(rec, s, a)
                audio, _ = librosa.load(str(ap), sr=SR, mono=True)
            except Exception as e:  # noqa: BLE001
                print(f"      SKIP {rec} {s}:{a} audio -> {e}")
                continue

            words = translit[(s, a)]
            ids, spans, oov = build_tokens(words, vocab)
            oov_all.extend(oov)
            if not ids:
                continue

            dur_ms = len(audio) / SR * 1000.0
            x = ((audio - audio.mean()) / (audio.std() + 1e-7)).astype(np.float32)
            t0 = time.time()
            logits = sess.run(["logits"], {"input_values": x[None, :]})[0][0]
            infer_s = time.time() - t0

            T = logits.shape[0]
            ms_per_frame = dur_ms / T
            logp = log_softmax(logits.astype(np.float64))
            tok_spans = ctc_forced_align(logp, ids)

            gt_segs = {seg[0]: seg for seg in g["segments"]}
            n_ok_start = n_ok_end = n_cmp = 0
            for wi, sp in enumerate(spans):
                seg = gt_segs.get(wi)
                if sp is None or seg is None:
                    continue
                tok_lo, tok_hi = sp
                a_lo = tok_spans[tok_lo]
                a_hi = tok_spans[tok_hi - 1]
                if a_lo is None or a_hi is None:
                    continue
                pred_start = a_lo[0] * ms_per_frame
                pred_end = (a_hi[1] + 1) * ms_per_frame
                e_start = pred_start - seg[2]
                e_end = pred_end - seg[3]
                tr = (words[wi]["tr"] or "").lower()
                per_word.append(
                    {
                        "reciter": rec, "speed": speed, "surah": s, "ayah": a,
                        "word": wi, "tr": tr,
                        "gt_start": seg[2], "gt_end": seg[3],
                        "pred_start": round(pred_start, 1), "pred_end": round(pred_end, 1),
                        "err_start": round(e_start, 1), "err_end": round(e_end, 1),
                        "ok_start": abs(e_start) <= G2_TOLERANCE_MS,
                        "ok_end": abs(e_end) <= G2_TOLERANCE_MS,
                        "has_q": "q" in tr, "has_hdot": "ḥ" in tr,
                        "word_pos_frac": round(wi / max(1, len(spans) - 1), 3),
                    }
                )
                n_cmp += 1
                n_ok_start += abs(e_start) <= G2_TOLERANCE_MS
                n_ok_end += abs(e_end) <= G2_TOLERANCE_MS

            per_clip.append(
                {
                    "reciter": rec, "speed": speed, "surah": s, "ayah": a,
                    "duration_s": round(dur_ms / 1000, 2), "n_words": n_cmp,
                    "frames": int(T), "ms_per_frame": round(ms_per_frame, 3),
                    "rtf": round(infer_s / (dur_ms / 1000), 3),
                    "acc_start": round(n_ok_start / n_cmp, 4) if n_cmp else None,
                    "acc_end": round(n_ok_end / n_cmp, 4) if n_cmp else None,
                }
            )
            manifest.append({"reciter": rec, "surah": s, "ayah": a,
                             "audio": f"{rec}/{s:03d}{a:03d}.mp3"})
        print(f"      {rec:<34} done ({time.time() - t_start:.0f}s)")

    # ----------------------------------------------------------- aggregate
    ok_all = [w["ok_start"] for w in per_word] + [w["ok_end"] for w in per_word]
    g2 = float(np.mean(ok_all)) if ok_all else 0.0
    errs = np.array([w["err_start"] for w in per_word] + [w["err_end"] for w in per_word])
    abs_errs = np.abs(errs)

    def pct(v, q):
        return round(float(np.percentile(v, q)), 1) if len(v) else None

    by_speed = {}
    for sp in {r["speed"] for r in per_clip}:
        ws = [w for w in per_word if w["speed"] == sp]
        o = [w["ok_start"] for w in ws] + [w["ok_end"] for w in ws]
        by_speed[sp] = {"n_words": len(ws),
                        "boundary_acc": round(float(np.mean(o)), 4) if o else None,
                        "median_abs_err_ms": pct(np.abs([w["err_start"] for w in ws]), 50)}

    by_reciter = {}
    for rec in RECITERS:
        ws = [w for w in per_word if w["reciter"] == rec]
        o = [w["ok_start"] for w in ws] + [w["ok_end"] for w in ws]
        by_reciter[rec] = {"n_words": len(ws),
                           "boundary_acc": round(float(np.mean(o)), 4) if o else None}

    def subset_acc(pred):
        ws = [w for w in per_word if pred(w)]
        o = [w["ok_start"] for w in ws] + [w["ok_end"] for w in ws]
        return {"n_words": len(ws),
                "boundary_acc": round(float(np.mean(o)), 4) if o else None,
                "median_abs_err_ms": pct(np.abs([w["err_start"] for w in ws]), 50) if ws else None}

    long_clips = {c["duration_s"] > 25 for c in per_clip}
    summary = {
        "step": "05_g2_alignment",
        "gate": "G2",
        "threshold_pct": G2_THRESHOLD,
        "tolerance_ms": G2_TOLERANCE_MS,
        "n_clips": len(per_clip),
        "n_words_compared": len(per_word),
        "n_boundaries_compared": len(ok_all),
        "G2_measured": round(g2, 4),
        "G2_pass": g2 >= G2_THRESHOLD,
        "start_boundary_acc": round(float(np.mean([w["ok_start"] for w in per_word])), 4),
        "end_boundary_acc": round(float(np.mean([w["ok_end"] for w in per_word])), 4),
        "abs_err_ms": {
            "mean": round(float(abs_errs.mean()), 1), "median": pct(abs_errs, 50),
            "p75": pct(abs_errs, 75), "p90": pct(abs_errs, 90),
            "p95": pct(abs_errs, 95), "p99": pct(abs_errs, 99),
            "max": round(float(abs_errs.max()), 1),
        },
        "signed_err_ms": {"mean": round(float(errs.mean()), 1), "median": pct(errs, 50)},
        "by_speed": by_speed,
        "by_reciter": by_reciter,
        "words_with_q": subset_acc(lambda w: w["has_q"]),
        "words_without_q": subset_acc(lambda w: not w["has_q"]),
        "words_with_hdot": subset_acc(lambda w: w["has_hdot"]),
        "words_without_hdot": subset_acc(lambda w: not w["has_hdot"]),
        "first_word_of_ayah": subset_acc(lambda w: w["word"] == 0),
        "later_words": subset_acc(lambda w: w["word"] > 0),
        "short_clips_le_25s": round(float(np.mean(
            [c["acc_start"] for c in per_clip if c["duration_s"] <= 25 and c["acc_start"] is not None])), 4),
        "long_clips_gt_25s": (round(float(np.mean(
            [c["acc_start"] for c in per_clip if c["duration_s"] > 25 and c["acc_start"] is not None])), 4)
            if True in long_clips else None),
        "oov_chars": sorted(set(oov_all)),
        "oov_count": len(oov_all),
        "mean_rtf_desktop_1thread": round(float(np.mean([c["rtf"] for c in per_clip])), 3),
        "sources": {
            "ground_truth": "cpfair/quran-align release-2016-11-24 (CC BY 4.0)",
            "audio": "everyayah.com per-ayah mp3 (CC BY 4.0, Tarteel AI)",
            "transliteration": "quran.com API v4 word-level (NO Buraaq data used)",
        },
    }

    (RESULTS / "05_g2_alignment.json").write_text(
        json.dumps({"summary": summary, "per_clip": per_clip}, indent=2, ensure_ascii=False),
        encoding="utf-8")
    (RESULTS / "05_g2_per_word.json").write_text(
        json.dumps(per_word, indent=2, ensure_ascii=False), encoding="utf-8")
    (CORPUS / "ratl-bench-v0.json").write_text(
        json.dumps({"ayat": AYAT, "reciters": list(RECITERS), "clips": manifest},
                   indent=2, ensure_ascii=False), encoding="utf-8")

    print("\n===== G2 =====")
    for k, v in summary.items():
        if k not in ("by_speed", "by_reciter"):
            print(f"  {k}: {v}")
    print("  by_speed:")
    for k, v in by_speed.items():
        print(f"    {k}: {v}")
    print("  by_reciter:")
    for k, v in by_reciter.items():
        print(f"    {k}: {v}")


if __name__ == "__main__":
    main()
