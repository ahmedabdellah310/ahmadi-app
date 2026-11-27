"""M2.0 - export the FROZEN exp2 checkpoint to ONNX, write vocab.json, measure G1 + CPU RTF.

The exp2 model has two heads. Per SPEC 2.1 the auxiliary boundary head is a
TRAINING-TIME objective only and is never used at inference, so it is dropped
here: the exported graph emits CTC logits alone.

Nothing about the frozen configuration is changed. This script only serialises
what already exists and measures the artifact.

RTF is measured through the FROZEN windowing geometry (15 s window / 10 s hop),
not the cheaper non-overlapping geometry used for the exp1 figure, so the number
is comparable to what production will actually do.
"""
import importlib.util
import json
import pathlib
import sys
import time

import numpy as np
import torch

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
x2 = _load("x2", "23_train_exp2.py")

CKPT = SPIKE / "models" / "exp2" / "best.pt"
OUT = ROOT / "models" / "out"
OUT.mkdir(parents=True, exist_ok=True)
FP32, INT8 = OUT / "ratl-align-v1-fp32.onnx", OUT / "ratl-align-v1-int8.onnx"
VOCAB = OUT / "vocab.json"
REPORT = ROOT / "models" / "export_exp2.json"


class CTCOnly(torch.nn.Module):
    """Frozen exp2 encoder + CTC head. Boundary head dropped (SPEC 2.1)."""

    def __init__(self, full):
        super().__init__()
        self.enc = full.enc
        self.head = full.head

    def forward(self, feats):
        return self.head(self.enc(feats).last_hidden_state)


def mb(p):
    return round(p.stat().st_size / (1024 * 1024), 4)


def sha(p):
    import hashlib
    return hashlib.sha256(p.read_bytes()).hexdigest()


def main():
    ck = torch.load(CKPT, map_location="cpu", weights_only=False)
    itos = ck["vocab"]
    full = x2.EncCTCBoundary(len(itos))
    full.load_state_dict(ck["model"])
    full.eval()
    n_all = sum(p.numel() for p in full.parameters())

    # ---- vocab.json, verified against the exp1 sidecar (handoff 4)
    VOCAB.write_text(json.dumps(itos, ensure_ascii=False), encoding="utf-8")
    ref = json.loads((SPIKE / "models" / "exp1" / "vocab.json").read_text(encoding="utf-8"))
    vocab_identical = (ref == itos)

    model = CTCOnly(full).eval()
    n_ctc = sum(p.numel() for p in model.parameters())

    # ---- parity: torch two-head vs torch CTC-only, same input
    dummy = torch.randn(1, tr.N_MELS, tr.MEL_FRAMES)
    with torch.no_grad():
        a = full(dummy)[0]
        b = model(dummy)
    head_drop_max = float((a - b).abs().max())

    torch.onnx.export(model, (dummy,), str(FP32), input_names=["input_features"],
                      output_names=["logits"],
                      dynamic_axes={"input_features": {0: "b"}, "logits": {0: "b"}},
                      opset_version=17, do_constant_folding=True, dynamo=False)

    from onnxruntime.quantization import QuantType, quantize_dynamic
    quantize_dynamic(str(FP32), str(INT8), weight_type=QuantType.QUInt8,
                     op_types_to_quantize=["MatMul"],
                     extra_options={"MatMulConstBOnly": True})

    # ---- torch vs onnx logits parity on real audio
    import librosa
    import onnxruntime as ort
    from transformers import WhisperFeatureExtractor

    fe = WhisperFeatureExtractor.from_pretrained(tr.BASE)
    fe.chunk_length = int(m1.WIN_S)
    fe.n_samples = int(m1.WIN_S * m1.SR)
    fe.nb_max_frames = tr.MEL_FRAMES

    so = ort.SessionOptions()
    so.intra_op_num_threads = 1
    s32 = ort.InferenceSession(str(FP32), so, providers=["CPUExecutionProvider"])
    s8 = ort.InferenceSession(str(INT8), so, providers=["CPUExecutionProvider"])

    AUD = SPIKE / ".cache" / "audio"
    probes = [AUD / "Husary_64kbps" / "001001.mp3",
              AUD / "Alafasy_128kbps" / "018001.mp3",
              AUD / "Husary_64kbps" / "002255.mp3"]
    probes = [p for p in probes if p.exists()]

    def feats(ch):
        f = fe(ch, sampling_rate=m1.SR, return_tensors="np", padding="max_length",
               max_length=int(m1.WIN_S * m1.SR))["input_features"][0][:, :tr.MEL_FRAMES]
        if f.shape[1] < tr.MEL_FRAMES:
            f = np.pad(f, ((0, 0), (0, tr.MEL_FRAMES - f.shape[1])), constant_values=f.min())
        return f[None].astype(np.float32)

    d32, d8, argmax_agree, ntot = [], [], 0, 0
    for p in probes:
        a_, _ = librosa.load(str(p), sr=m1.SR, mono=True)
        x = feats(a_[:int(m1.WIN_S * m1.SR)])
        with torch.no_grad():
            t = model(torch.from_numpy(x))[0].numpy()
        o32 = s32.run(["logits"], {"input_features": x})[0][0]
        o8 = s8.run(["logits"], {"input_features": x})[0][0]
        d32.append(float(np.abs(t - o32).max()))
        d8.append(float(np.abs(t - o8).max()))
        argmax_agree += int((t.argmax(-1) == o8.argmax(-1)).sum())
        ntot += t.shape[0]

    # ---- RTF through the FROZEN overlap windowing (15 s / 10 s hop)
    def rtf_for(sess, audio):
        win, hop = int(m1.WIN_S * m1.SR), int(m1.HOP_S * m1.SR)
        n = len(audio)
        t0 = time.perf_counter()
        if n <= win:
            sess.run(["logits"], {"input_features": feats(audio)})
        else:
            st = 0
            while st < n:
                sess.run(["logits"], {"input_features": feats(audio[st:st + win])})
                if st + win >= n:
                    break
                st += hop
        return (time.perf_counter() - t0) / (n / m1.SR)

    per_clip = []
    for p in probes:
        a_, _ = librosa.load(str(p), sr=m1.SR, mono=True)
        rtf_for(s8, a_[:m1.SR])                      # warm up
        r = min(rtf_for(s8, a_) for _ in range(3))   # best of 3
        per_clip.append({"clip": p.parent.name + "/" + p.name,
                         "dur_s": round(len(a_) / m1.SR, 2), "rtf": round(r, 5)})
    rtf = float(np.mean([c["rtf"] for c in per_clip]))

    rep = {
        "step": "M2.0 export_exp2",
        "checkpoint": {"path": str(CKPT.relative_to(ROOT)), "epoch": int(ck["epoch"]),
                       "val": round(float(ck["val"]), 4)},
        "boundary_head_dropped": True,
        "params_exp2_full": n_all, "params_exported_ctc_only": n_ctc,
        "vocab_size": len(itos), "vocab_identical_to_exp1": vocab_identical,
        "head_drop_logit_max_abs_diff": head_drop_max,
        "G1": {"fp32_onnx_mb": mb(FP32), "int8_onnx_mb": mb(INT8),
               "ceiling_mb": 120.0, "pass": mb(INT8) <= 120.0,
               "exp1_reference_int8_mb": 10.07},
        "onnx_parity": {"torch_vs_onnx_fp32_max_abs": max(d32),
                        "torch_vs_onnx_int8_max_abs": max(d8),
                        "int8_argmax_agreement": round(argmax_agree / ntot, 6),
                        "frames_compared": ntot},
        "G5_basis": {"desktop_cpu_rtf_1thread": round(rtf, 5),
                     "windowing": "FROZEN 15 s window / 10 s hop (overlap)",
                     "includes_feature_extraction": True,
                     "per_clip": per_clip, "ceiling_rtf": 0.5,
                     "projected_device_range_3x_5x": [round(rtf * 3, 4), round(rtf * 5, 4)],
                     "verdict_estimated": "PLAUSIBLE" if rtf * 5 <= 0.5 else
                                          "MARGINAL" if rtf * 3 <= 0.5 else "IMPLAUSIBLE",
                     "note": "ESTIMATE. G5 is only satisfied when measured on the SPEC 7.3 device."},
        "artifacts": {"fp32": {"name": FP32.name, "sha256": sha(FP32)},
                      "int8": {"name": INT8.name, "sha256": sha(INT8)},
                      "vocab": {"name": VOCAB.name, "sha256": sha(VOCAB)}},
    }
    REPORT.write_text(json.dumps(rep, indent=2, ensure_ascii=False), encoding="utf-8")
    print(json.dumps(rep, indent=2, ensure_ascii=False))


if __name__ == "__main__":
    main()
