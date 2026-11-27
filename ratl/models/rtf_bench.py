"""M2.0 - CPU RTF for both exported artifacts, through the FROZEN windowing.

Single-threaded CPU, feature extraction included in the timed region, 15 s
window / 10 s hop, which is what production does. Best-of-N to suppress
scheduler noise. This is the G5 *basis*, not G5: G5 is only satisfied when
measured on the SPEC 7.3 device.
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


def _load(name, fn):
    sp = importlib.util.spec_from_file_location(name, SPIKE / fn)
    m = importlib.util.module_from_spec(sp)
    sys.modules[name] = m
    sp.loader.exec_module(m)
    return m


tr = _load("tr", "10_train_exp1.py")
m1 = _load("m1", "20_m1_select.py")

OUT = ROOT / "models" / "out"
REPORT = ROOT / "models" / "rtf_bench.json"
REPS = 5


def main():
    import librosa
    import onnxruntime as ort
    from transformers import WhisperFeatureExtractor

    fe = WhisperFeatureExtractor.from_pretrained(tr.BASE)
    fe.chunk_length = int(m1.WIN_S)
    fe.n_samples = int(m1.WIN_S * m1.SR)
    fe.nb_max_frames = tr.MEL_FRAMES

    def mel(ch):
        f = fe(ch, sampling_rate=m1.SR, return_tensors="np", padding="max_length",
               max_length=int(m1.WIN_S * m1.SR))["input_features"][0][:, :tr.MEL_FRAMES]
        if f.shape[1] < tr.MEL_FRAMES:
            f = np.pad(f, ((0, 0), (0, tr.MEL_FRAMES - f.shape[1])), constant_values=f.min())
        return f[None].astype(np.float32)

    so = ort.SessionOptions()
    so.intra_op_num_threads = 1
    so.inter_op_num_threads = 1

    AUD = SPIKE / ".cache" / "audio"
    clips = [AUD / "Husary_64kbps" / "001001.mp3",
             AUD / "Alafasy_128kbps" / "018001.mp3",
             AUD / "Abdul_Basit_Mujawwad_128kbps" / "001007.mp3",
             AUD / "Husary_64kbps" / "002255.mp3"]
    clips = [c for c in clips if c.exists()]
    audio = {c: librosa.load(str(c), sr=m1.SR, mono=True)[0] for c in clips}

    # feature-extraction cost alone, for the Kotlin port budget
    a0 = audio[clips[0]][:int(m1.WIN_S * m1.SR)]
    mel(a0)
    t0 = time.perf_counter()
    for _ in range(20):
        mel(a0)
    mel_ms = (time.perf_counter() - t0) / 20 * 1000

    out = {"step": "M2.0 rtf_bench", "threads": 1, "reps": REPS,
           "windowing": "FROZEN 15 s window / 10 s hop",
           "feature_extraction_included": True,
           "mel_ms_per_15s_window": round(mel_ms, 2),
           "artifacts": {}}

    for kind in ("fp32", "int8"):
        sess = ort.InferenceSession(str(OUT / f"ratl-align-v1-{kind}.onnx"), so,
                                    providers=["CPUExecutionProvider"])

        def run(a):
            win, hop = int(m1.WIN_S * m1.SR), int(m1.HOP_S * m1.SR)
            n = len(a)
            t = time.perf_counter()
            if n <= win:
                sess.run(["logits"], {"input_features": mel(a)})
            else:
                st = 0
                while st < n:
                    sess.run(["logits"], {"input_features": mel(a[st:st + win])})
                    if st + win >= n:
                        break
                    st += hop
            return (time.perf_counter() - t) / (n / m1.SR)

        run(audio[clips[0]][:m1.SR])  # warm up
        per = []
        for c in clips:
            rs = [run(audio[c]) for _ in range(REPS)]
            per.append({"clip": f"{c.parent.name}/{c.name}",
                        "dur_s": round(len(audio[c]) / m1.SR, 2),
                        "rtf_best": round(min(rs), 5),
                        "rtf_median": round(float(np.median(rs)), 5)})
        rtf = float(np.mean([p["rtf_best"] for p in per]))
        out["artifacts"][kind] = {
            "size_mb": round((OUT / f"ratl-align-v1-{kind}.onnx").stat().st_size / 1048576, 4),
            "desktop_cpu_rtf_1thread": round(rtf, 5),
            "per_clip": per,
            "projected_device_3x": round(rtf * 3, 4),
            "projected_device_5x": round(rtf * 5, 4),
            "G5_ceiling": 0.5,
            "headroom_vs_ceiling_at_5x": round(0.5 / (rtf * 5), 1),
            "verdict_estimated": "PLAUSIBLE" if rtf * 5 <= 0.5 else
                                 "MARGINAL" if rtf * 3 <= 0.5 else "IMPLAUSIBLE",
        }

    out["note"] = ("ESTIMATE ONLY. G5 requires measurement on the Xiaomi Redmi "
                   "Note 12 Pro+ 5G per SPEC 7.3. The 3x-5x device factor is an "
                   "assumption carried from M1, not a measurement.")
    REPORT.write_text(json.dumps(out, indent=2), encoding="utf-8")
    print(json.dumps(out, indent=2))


if __name__ == "__main__":
    main()
