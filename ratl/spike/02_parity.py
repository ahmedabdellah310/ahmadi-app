"""
RATL Week 1 spike — Day 2, step 2: fp32 vs int8 parity on REAL continuous audio.

WEEK1_PLAN.md Day 2 requires: "Numerical parity check, fp32 vs int8, on 10 clips
-- quantify quantization-induced degradation separately from model-generalization
degradation."

NOTE: SPEC.md v0.2 defines NO numeric parity threshold. This step MEASURES;
it does not gate. Proposed thresholds are reported for owner approval, not
applied retroactively.

Audio source: tarteel-ai/everyayah (CC BY 4.0) -- ayah-level, i.e. CONTINUOUS
recitation, which is the regime the model was NOT trained on. Attribution:
Tarteel AI.

THROWAWAY SPIKE CODE. Not RATL Core. See WEEK1_PLAN.md section 2.
"""

import json
import os
import pathlib
import time

HERE = pathlib.Path(__file__).parent
os.environ.setdefault("HF_HOME", str(HERE / ".cache"))

import numpy as np  # noqa: E402

MODELS = HERE / "models"
RESULTS = HERE / "results"
FP32_PATH = MODELS / "w2v2-quran-fp32.onnx"
INT8_PATH = MODELS / "w2v2-quran-int8.onnx"

SR = 16000
N_CLIPS = 10


# Direct per-ayah audio from everyayah.com -- the origin of the
# tarteel-ai/everyayah dataset. Small files, no multi-GB parquet shards.
# Spike/research use only; RATL redistributes no audio.
EVERYAYAH = "https://everyayah.com/data"
AUDIO_CACHE = HERE / ".cache" / "audio"

# (reciter_folder, surah, ayah, note) -- spread over reciters, lengths, styles.
CLIP_SPEC = [
    ("Husary_128kbps", 1, 1, "murattal, measured"),
    ("Husary_128kbps", 2, 1, "very short (alif-lam-mim)"),
    ("Husary_128kbps", 36, 1, "short"),
    ("Alafasy_128kbps", 1, 2, "moderate tempo"),
    ("Alafasy_128kbps", 18, 1, "moderate tempo"),
    ("Abdul_Basit_Murattal_192kbps", 112, 1, "slow murattal"),
    ("Abdul_Basit_Murattal_192kbps", 55, 1, "slow murattal"),
    ("Abdurrahmaan_As-Sudais_192kbps", 114, 1, "faster tempo"),
    ("Abdurrahmaan_As-Sudais_192kbps", 3, 8, "faster tempo"),
    ("Minshawy_Murattal_128kbps", 2, 255, "long ayah (ayat al-kursi)"),
]


def _fetch(reciter: str, surah: int, ayah: int) -> pathlib.Path:
    import urllib.request

    name = f"{surah:03d}{ayah:03d}.mp3"
    dest = AUDIO_CACHE / reciter / name
    if dest.exists() and dest.stat().st_size > 0:
        return dest
    dest.parent.mkdir(parents=True, exist_ok=True)
    url = f"{EVERYAYAH}/{reciter}/{name}"
    req = urllib.request.Request(url, headers={"User-Agent": "RATL-spike/0.1"})
    with urllib.request.urlopen(req, timeout=60) as r, open(dest, "wb") as f:
        f.write(r.read())
    return dest


def _decode(path: pathlib.Path) -> np.ndarray:
    import librosa

    arr, sr = librosa.load(str(path), sr=SR, mono=True)
    return np.asarray(arr, dtype=np.float32)


def load_clips(n: int):
    """Fetch ayah-level (continuous) clips from everyayah.com."""
    print(f"[data] fetching {n} clips from everyayah.com ...")
    clips = []
    for reciter, surah, ayah, note in CLIP_SPEC[:n]:
        try:
            p = _fetch(reciter, surah, ayah)
            arr = _decode(p)
        except Exception as e:  # noqa: BLE001
            print(f"       SKIP {reciter} {surah}:{ayah} -> {e}")
            continue
        dur = len(arr) / SR
        clips.append(
            {
                "idx": f"{surah:03d}{ayah:03d}",
                "reciter": reciter,
                "text": note,
                "duration_s": round(dur, 2),
                "audio": arr,
            }
        )
        print(f"       {reciter:<32} {surah:>3}:{ayah:<4} {dur:>6.2f}s  ({note})")
    return clips


def normalize(x: np.ndarray) -> np.ndarray:
    return ((x - x.mean()) / (x.std() + 1e-7)).astype(np.float32)


def softmax(z: np.ndarray) -> np.ndarray:
    z = z - z.max(axis=-1, keepdims=True)
    e = np.exp(z)
    return e / e.sum(axis=-1, keepdims=True)


def greedy_ctc(logits: np.ndarray, id2tok: dict, blank: int) -> str:
    ids = logits.argmax(-1)
    out, prev = [], -1
    for i in ids:
        if i != prev and i != blank:
            out.append(id2tok.get(int(i), ""))
        prev = i
    return "".join(out)


def cer(a: str, b: str) -> float:
    """Levenshtein / len(reference a)."""
    if not a:
        return 0.0 if not b else 1.0
    prev = list(range(len(b) + 1))
    for i, ca in enumerate(a, 1):
        cur = [i]
        for j, cb in enumerate(b, 1):
            cur.append(min(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + (ca != cb)))
        prev = cur
    return prev[-1] / len(a)


def main() -> None:
    import onnxruntime as ort
    from transformers import Wav2Vec2Processor

    proc = Wav2Vec2Processor.from_pretrained("TBOGamer22/wav2vec2-quran-phonetics")
    vocab = proc.tokenizer.get_vocab()
    id2tok = {v: k for k, v in vocab.items()}
    blank = 37

    so = ort.SessionOptions()
    so.intra_op_num_threads = 1
    sess = {
        "fp32": ort.InferenceSession(str(FP32_PATH), so, providers=["CPUExecutionProvider"]),
        "int8": ort.InferenceSession(str(INT8_PATH), so, providers=["CPUExecutionProvider"]),
    }

    clips = load_clips(N_CLIPS)
    if not clips:
        raise SystemExit("no clips loaded")

    rows = []
    for c in clips:
        feed = {"input_values": normalize(c["audio"])[None, :]}
        lg, tm = {}, {}
        for tag in ("fp32", "int8"):
            t0 = time.time()
            lg[tag] = sess[tag].run(["logits"], feed)[0][0]
            tm[tag] = time.time() - t0

        p32, p8 = softmax(lg["fp32"]), softmax(lg["int8"])
        a32, a8 = lg["fp32"].argmax(-1), lg["int8"].argmax(-1)

        t32 = greedy_ctc(lg["fp32"], id2tok, blank)
        t8 = greedy_ctc(lg["int8"], id2tok, blank)

        rows.append(
            {
                "clip": c["idx"],
                "reciter": c["reciter"],
                "duration_s": c["duration_s"],
                "frames": int(lg["fp32"].shape[0]),
                "argmax_agreement": round(float((a32 == a8).mean()), 4),
                "logit_mae": round(float(np.abs(lg["fp32"] - lg["int8"]).mean()), 4),
                "logit_max_abs": round(float(np.abs(lg["fp32"] - lg["int8"]).max()), 4),
                "posterior_mae": round(float(np.abs(p32 - p8).mean()), 6),
                "top1_conf_fp32": round(float(p32.max(-1).mean()), 4),
                "top1_conf_int8": round(float(p8.max(-1).mean()), 4),
                "decode_identical": t32 == t8,
                "decode_cer": round(cer(t32, t8), 4),
                "rtf_fp32": round(tm["fp32"] / c["duration_s"], 3),
                "rtf_int8": round(tm["int8"] / c["duration_s"], 3),
                "text_fp32": t32,
                "text_int8": t8,
                "ref_text": c["text"],
            }
        )
        print(
            f"  clip {c['idx']:>4} {c['duration_s']:>5.1f}s  "
            f"argmax={rows[-1]['argmax_agreement']:.4f}  "
            f"CER={rows[-1]['decode_cer']:.4f}  "
            f"rtf32={rows[-1]['rtf_fp32']:.2f} rtf8={rows[-1]['rtf_int8']:.2f}"
        )

    def agg(k):
        return round(float(np.mean([r[k] for r in rows])), 6)

    summary = {
        "step": "02_parity",
        "n_clips": len(rows),
        "audio_source": "tarteel-ai/everyayah (CC BY 4.0), attribution: Tarteel AI",
        "regime": "continuous ayah-level recitation (out-of-training-domain)",
        "mean_argmax_agreement": agg("argmax_agreement"),
        "min_argmax_agreement": round(min(r["argmax_agreement"] for r in rows), 4),
        "mean_decode_cer": agg("decode_cer"),
        "max_decode_cer": round(max(r["decode_cer"] for r in rows), 4),
        "n_decode_identical": sum(r["decode_identical"] for r in rows),
        "mean_posterior_mae": agg("posterior_mae"),
        "mean_logit_mae": agg("logit_mae"),
        "mean_top1_conf_fp32": agg("top1_conf_fp32"),
        "mean_top1_conf_int8": agg("top1_conf_int8"),
        "mean_rtf_fp32_desktop_1thread": agg("rtf_fp32"),
        "mean_rtf_int8_desktop_1thread": agg("rtf_int8"),
        "SPEC_NOTE": "SPEC v0.2 defines no numeric parity threshold; this is a measurement, not a gate.",
        "clips": rows,
    }

    out = RESULTS / "02_parity.json"
    out.write_text(json.dumps(summary, indent=2, ensure_ascii=False), encoding="utf-8")
    print("\n--- PARITY SUMMARY ---")
    for k, v in summary.items():
        if k != "clips":
            print(f"  {k}: {v}")
    print(f"\nwrote {out}")


if __name__ == "__main__":
    main()
