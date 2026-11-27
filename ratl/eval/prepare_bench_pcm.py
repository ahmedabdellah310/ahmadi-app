"""Pre-decode RATL-Bench-v0 to raw PCM for ratl-eval.

SPEC 5.2 takes PCM_f32_16k as input, so audio decoding is explicitly NOT part of
RATL's contract. Decoding here rather than in Kotlin also removes MP3 decoder and
resampler differences as a confound: the Kotlin path is handed byte-identical
samples to the ones the Python reference used, so any G2 difference is a port
difference and nothing else.

Writes int16 mono 16 kHz PCM plus a manifest carrying the target words and the
ground-truth word segments. Output is gitignored - it contains transliterations,
which the 5.5 text-source policy keeps out of the published repository.
"""
import importlib.util
import json
import pathlib
import sys

import numpy as np

ROOT = pathlib.Path(__file__).resolve().parent.parent
SPIKE = ROOT / "spike"
sys.path.insert(0, str(SPIKE))


def _load(name, fn):
    sp = importlib.util.spec_from_file_location(name, SPIKE / fn)
    m = importlib.util.module_from_spec(sp)
    sys.modules[name] = m
    sp.loader.exec_module(m)
    return m


g2 = _load("g2", "05_g2_alignment.py")

OUT = ROOT / "eval" / "bench_pcm"
OUT.mkdir(parents=True, exist_ok=True)


def main():
    import librosa
    gt = g2.load_gt()
    translit = {k: g2.fetch_translit(*k) for k in g2.AYAT}
    items, total = [], 0
    for rec, (_, speed) in g2.RECITERS.items():
        (OUT / rec).mkdir(parents=True, exist_ok=True)
        for s, a in g2.AYAT:
            g = gt[rec].get((s, a))
            if not g:
                continue
            src = SPIKE / ".cache" / "audio" / rec / f"{s:03d}{a:03d}.mp3"
            if not src.exists():
                continue
            audio, _ = librosa.load(str(src), sr=g2.SR, mono=True)
            pcm = np.clip(np.round(audio * 32768.0), -32768, 32767).astype(np.int16)
            dst = OUT / rec / f"{s:03d}{a:03d}.s16"
            dst.write_bytes(pcm.tobytes())
            total += pcm.nbytes
            items.append({
                "reciter": rec, "speed": speed, "surah": s, "ayah": a,
                "pcm": f"{rec}/{dst.name}", "n_samples": int(pcm.size),
                "words": [(w["tr"] or "").strip().lower() for w in translit[(s, a)]],
                "segments": [[int(x[0]), int(x[2]), int(x[3])] for x in g["segments"]],
            })
    man = {"corpus": "RATL-Bench-v0", "sample_rate": g2.SR,
           "format": "int16 LE mono; divide by 32768.0 for float",
           "tolerance_ms": g2.G2_TOLERANCE_MS, "threshold": g2.G2_THRESHOLD,
           "frozen_G2": 0.8570, "n_items": len(items), "items": items}
    (OUT / "manifest.json").write_text(json.dumps(man, ensure_ascii=False), encoding="utf-8")
    print(f"wrote {len(items)} clips, {total/1048576:.1f} MB -> {OUT}")


if __name__ == "__main__":
    main()
