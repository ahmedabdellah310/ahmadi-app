"""Export trained Experiment 1 model to ONNX int8; measure G1 size and CPU RTF (G5 basis)."""
import importlib.util
import json
import pathlib
import sys
import time

import numpy as np
import torch

HERE = pathlib.Path(__file__).parent
spec = importlib.util.spec_from_file_location("tr", HERE / "10_train_exp1.py")
tr = importlib.util.module_from_spec(spec)
sys.modules["tr"] = tr
spec.loader.exec_module(tr)

OUT = HERE / "models" / "exp1"
RESULTS = HERE / "results"
AUDIO = HERE / ".cache" / "audio"
FP32, INT8 = OUT / "exp1-fp32.onnx", OUT / "exp1-int8.onnx"

ck = torch.load(OUT / "best.pt", map_location="cpu", weights_only=False)
itos = ck["vocab"]
model = tr.EncCTC(len(itos))
model.load_state_dict(ck["model"])
model.eval()
n = sum(p.numel() for p in model.parameters())

dummy = torch.randn(1, tr.N_MELS, tr.MEL_FRAMES)
torch.onnx.export(model, (dummy,), str(FP32), input_names=["input_features"],
                  output_names=["logits"],
                  dynamic_axes={"input_features": {0: "b"}, "logits": {0: "b"}},
                  opset_version=17, do_constant_folding=True, dynamo=False)
from onnxruntime.quantization import QuantType, quantize_dynamic  # noqa: E402

quantize_dynamic(str(FP32), str(INT8), weight_type=QuantType.QUInt8,
                 op_types_to_quantize=["MatMul"], extra_options={"MatMulConstBOnly": True})


def mb(p):
    return round(p.stat().st_size / (1024 * 1024), 2)


import librosa  # noqa: E402
import onnxruntime as ort  # noqa: E402
from transformers import WhisperFeatureExtractor  # noqa: E402

fe = WhisperFeatureExtractor.from_pretrained(tr.BASE)
fe.chunk_length = int(tr.WINDOW_S)
fe.n_samples = int(tr.WINDOW_S * tr.SR)
fe.nb_max_frames = tr.MEL_FRAMES

so = ort.SessionOptions()
so.intra_op_num_threads = 1
sess = ort.InferenceSession(str(INT8), so, providers=["CPUExecutionProvider"])

rtfs = []
for p in (AUDIO / "Husary_64kbps" / "001001.mp3",
          AUDIO / "Alafasy_128kbps" / "018001.mp3",
          AUDIO / "Husary_64kbps" / "002255.mp3"):
    if not p.exists():
        continue
    a, _ = librosa.load(str(p), sr=tr.SR, mono=True)
    dur = len(a) / tr.SR
    win = int(tr.WINDOW_S * tr.SR)
    t0 = time.time()
    for i in range(0, max(1, len(a)), win):
        ch = a[i:i + win]
        f = fe(ch, sampling_rate=tr.SR, return_tensors="np",
               padding="max_length", max_length=win)["input_features"][0][:, :tr.MEL_FRAMES]
        if f.shape[1] < tr.MEL_FRAMES:
            f = np.pad(f, ((0, 0), (0, tr.MEL_FRAMES - f.shape[1])), constant_values=f.min())
        sess.run(["logits"], {"input_features": f[None].astype(np.float32)})
    rtfs.append((time.time() - t0) / dur)

rtf = round(float(np.mean(rtfs)), 4)
rep = {"step": "14_export_exp1", "params": n, "params_M": round(n / 1e6, 2),
       "fp32_onnx_mb": mb(FP32), "int8_onnx_mb": mb(INT8),
       "G1_ceiling_mb": 120.0, "G1_pass": mb(INT8) <= 120.0,
       "desktop_cpu_rtf_1thread": rtf, "per_clip_rtf": [round(r, 4) for r in rtfs],
       "G5_ceiling_rtf": 0.5,
       "G5_projected_device_range": [round(rtf * 3, 4), round(rtf * 5, 4)],
       "G5_verdict_estimated": "PLAUSIBLE" if rtf * 5 <= 0.5 else
                               "MARGINAL" if rtf * 3 <= 0.5 else "IMPLAUSIBLE",
       "note": "includes log-mel feature extraction in the timed loop",
       "comparison_failed_model": {"int8_onnx_mb": 116.28, "desktop_rtf_bench": 0.331}}
(RESULTS / "14_export_exp1.json").write_text(json.dumps(rep, indent=2), encoding="utf-8")
for k, v in rep.items():
    print(f"  {k}: {v}")
