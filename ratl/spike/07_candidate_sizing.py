"""
RATL Week 1 spike — Rung 3, step 3: candidate base-model feasibility sizing.

Measures parameter counts, exports + int8-quantizes each candidate, and measures
desktop single-thread RTF on the SAME audio, so G1 (<=120 MB) and G5 (<=0.5 RTF)
can be screened BEFORE any GPU time is spent.

MEASURED here: param counts, int8 file size, desktop RTF.
ESTIMATED here: on-device RTF (desktop RTF x device factor). Flagged as such.

Does not modify RATL-Bench-v0 or the alignment harness.
"""

import json
import os
import pathlib
import time

HERE = pathlib.Path(__file__).parent
os.environ.setdefault("HF_HOME", str(HERE / ".cache"))

import numpy as np  # noqa: E402
import torch  # noqa: E402

OUT = HERE / "models" / "candidates"
OUT.mkdir(parents=True, exist_ok=True)
RESULTS = HERE / "results"
AUDIO = HERE / ".cache" / "audio"

SR = 16000
VOCAB = 40  # keep identical to the failed model's head so comparison is clean
G1_CEILING_MB = 120.0
G5_CEILING_RTF = 0.5
# Device factor: Dimensity 1080 (2x A78 @2.6GHz) vs this desktop core.
# ASSUMPTION, not measured. Reported as a range; real value comes from Day 5.
DEVICE_FACTOR = (3.0, 5.0)


def mb(p: pathlib.Path) -> float:
    return round(p.stat().st_size / (1024 * 1024), 2)


def bench_audio():
    """Three real clips of differing length from the existing cache."""
    import librosa

    picks = [
        AUDIO / "Husary_64kbps" / "001001.mp3",
        AUDIO / "Alafasy_128kbps" / "018001.mp3",
        AUDIO / "Husary_64kbps" / "002255.mp3",
    ]
    clips = []
    for p in picks:
        if not p.exists():
            continue
        a, _ = librosa.load(str(p), sr=SR, mono=True)
        a = ((a - a.mean()) / (a.std() + 1e-7)).astype(np.float32)
        clips.append((p.name, a))
    return clips


class W2V2Trunc(torch.nn.Module):
    """wav2vec2-base truncated to n transformer layers + CTC head."""

    def __init__(self, n_layers: int, vocab: int = VOCAB):
        super().__init__()
        from transformers import Wav2Vec2Config, Wav2Vec2Model

        cfg = Wav2Vec2Config.from_pretrained("facebook/wav2vec2-base")
        cfg.num_hidden_layers = n_layers
        cfg.vocab_size = vocab
        self.enc = Wav2Vec2Model(cfg)
        self.head = torch.nn.Linear(cfg.hidden_size, vocab)
        # auxiliary word-boundary head (multi-task) -- negligible params
        self.bnd = torch.nn.Linear(cfg.hidden_size, 2)

    def forward(self, x):
        h = self.enc(x).last_hidden_state
        return self.head(h)


class WhisperEncCTC(torch.nn.Module):
    """Whisper encoder + CTC head. Encoder only; decoder discarded."""

    def __init__(self, name: str, vocab: int = VOCAB):
        super().__init__()
        from transformers import WhisperConfig, WhisperModel

        cfg = WhisperConfig.from_pretrained(name)
        self.enc = WhisperModel(cfg).encoder
        self.head = torch.nn.Linear(cfg.d_model, vocab)
        self.bnd = torch.nn.Linear(cfg.d_model, 2)
        self.n_mels = cfg.num_mel_bins
        self.max_frames = cfg.max_source_positions * 2

    def forward(self, feats):
        h = self.enc(feats).last_hidden_state
        return self.head(h)


def count(m: torch.nn.Module) -> int:
    return sum(p.numel() for p in m.parameters())


def export_quant(model, dummy, tag, input_name):
    fp32 = OUT / f"{tag}-fp32.onnx"
    int8 = OUT / f"{tag}-int8.onnx"
    model.eval()
    torch.onnx.export(
        model, (dummy,), str(fp32),
        input_names=[input_name], output_names=["logits"],
        dynamic_axes={input_name: {0: "b", 1: "t"}, "logits": {0: "b", 1: "f"}},
        opset_version=17, do_constant_folding=True, dynamo=False,
    )
    from onnxruntime.quantization import QuantType, quantize_dynamic

    quantize_dynamic(str(fp32), str(int8), weight_type=QuantType.QUInt8,
                     op_types_to_quantize=["MatMul"],
                     extra_options={"MatMulConstBOnly": True})
    return fp32, int8


def measure_rtf(onnx_path, clips, is_whisper, n_mels=80):
    import onnxruntime as ort

    so = ort.SessionOptions()
    so.intra_op_num_threads = 1
    sess = ort.InferenceSession(str(onnx_path), so, providers=["CPUExecutionProvider"])
    name = sess.get_inputs()[0].name
    rtfs = []
    for _, a in clips:
        dur = len(a) / SR
        if is_whisper:
            import librosa

            feats = librosa.feature.melspectrogram(
                y=a, sr=SR, n_fft=400, hop_length=160, n_mels=n_mels)
            feats = np.log(np.maximum(feats, 1e-10)).astype(np.float32)[None, :, :]
            x = {name: feats}
        else:
            x = {name: a[None, :]}
        t0 = time.time()
        sess.run(["logits"], x)
        rtfs.append((time.time() - t0) / dur)
    return round(float(np.mean(rtfs)), 3), [round(r, 3) for r in rtfs]


def main():
    clips = bench_audio()
    print(f"[bench] {len(clips)} clips: " +
          ", ".join(f"{n}({len(a)/SR:.1f}s)" for n, a in clips))

    cands = []

    # --- wav2vec2-base truncations -------------------------------------------
    for n_layers in (12, 8, 6, 4):
        tag = f"w2v2-base-L{n_layers}"
        print(f"\n[{tag}] building ...")
        m = W2V2Trunc(n_layers)
        n = count(m)
        dummy = torch.randn(1, SR * 4)
        try:
            fp32, int8 = export_quant(m, dummy, tag, "input_values")
            rtf, per = measure_rtf(int8, clips, is_whisper=False)
            cands.append({
                "name": tag, "family": "wav2vec2-base truncated",
                "params": n, "params_M": round(n / 1e6, 2),
                "fp32_onnx_mb": mb(fp32), "int8_onnx_mb": mb(int8),
                "desktop_rtf_1thread": rtf, "per_clip_rtf": per,
            })
            print(f"      {n/1e6:.2f}M params  int8 {mb(int8)} MB  rtf {rtf}")
        except Exception as e:  # noqa: BLE001
            print(f"      FAILED: {e}")
        del m

    # --- whisper encoders ----------------------------------------------------
    for name, tag in (("openai/whisper-tiny", "whisper-tiny-enc"),
                      ("openai/whisper-base", "whisper-base-enc")):
        print(f"\n[{tag}] building ...")
        try:
            m = WhisperEncCTC(name)
            n = count(m)
            dummy = torch.randn(1, m.n_mels, 300)
            fp32, int8 = export_quant(m, dummy, tag, "input_features")
            rtf, per = measure_rtf(int8, clips, is_whisper=True, n_mels=m.n_mels)
            cands.append({
                "name": tag, "family": "whisper encoder + CTC head",
                "params": n, "params_M": round(n / 1e6, 2),
                "fp32_onnx_mb": mb(fp32), "int8_onnx_mb": mb(int8),
                "desktop_rtf_1thread": rtf, "per_clip_rtf": per,
                "note": "encoder only; decoder discarded",
            })
            print(f"      {n/1e6:.2f}M params  int8 {mb(int8)} MB  rtf {rtf}")
            del m
        except Exception as e:  # noqa: BLE001
            print(f"      FAILED: {e}")

    # --- screening -----------------------------------------------------------
    for c in cands:
        c["G1_pass_measured"] = c["int8_onnx_mb"] <= G1_CEILING_MB
        lo = round(c["desktop_rtf_1thread"] * DEVICE_FACTOR[0], 3)
        hi = round(c["desktop_rtf_1thread"] * DEVICE_FACTOR[1], 3)
        c["G5_projected_rtf_range"] = [lo, hi]
        c["G5_verdict_estimated"] = (
            "PLAUSIBLE" if hi <= G5_CEILING_RTF else
            "MARGINAL" if lo <= G5_CEILING_RTF else "IMPLAUSIBLE"
        )

    rep = {
        "step": "07_candidate_sizing",
        "measured": ["params", "fp32_onnx_mb", "int8_onnx_mb", "desktop_rtf_1thread"],
        "estimated": ["G5_projected_rtf_range (desktop RTF x device factor)"],
        "assumptions": {
            "device_factor_range": DEVICE_FACTOR,
            "basis": "Dimensity 1080 2x Cortex-A78 @2.6GHz vs desktop core; NOT measured until Day 5",
            "vocab_size": VOCAB,
            "weights": "randomly initialised -- sizing/speed only, says nothing about accuracy",
        },
        "spec_requirements": {"G1_ceiling_mb": G1_CEILING_MB, "G5_ceiling_rtf": G5_CEILING_RTF},
        "reference_measured_failed_model": {
            "name": "TBOGamer22/wav2vec2-quran-phonetics",
            "params_M": 94.4, "int8_onnx_mb": 116.28,
            "desktop_rtf_bench": 0.331, "G2": 0.5611,
        },
        "candidates": cands,
    }
    (RESULTS / "07_candidate_sizing.json").write_text(
        json.dumps(rep, indent=2), encoding="utf-8")

    print("\n===== CANDIDATE SCREEN =====")
    print(f"{'candidate':<22}{'params':>9}{'int8 MB':>10}{'rtf(desk)':>11}"
          f"{'G1':>6}  {'G5 (est)':<12}")
    for c in cands:
        print(f"{c['name']:<22}{c['params_M']:>8.2f}M{c['int8_onnx_mb']:>10.2f}"
              f"{c['desktop_rtf_1thread']:>11.3f}"
              f"{('PASS' if c['G1_pass_measured'] else 'FAIL'):>6}  "
              f"{c['G5_verdict_estimated']:<12} {c['G5_projected_rtf_range']}")


if __name__ == "__main__":
    main()
