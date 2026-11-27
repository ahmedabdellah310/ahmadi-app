"""
RATL Week 1 spike — Day 2, step 1: ONNX export + int8 dynamic quantization.

Measures SPEC.md G1 (int8 model size <= 120 MB).

THROWAWAY SPIKE CODE. Not RATL Core. Not the SDK. See WEEK1_PLAN.md section 2.
"""

import json
import os
import pathlib
import time

HERE = pathlib.Path(__file__).parent
os.environ.setdefault("HF_HOME", str(HERE / ".cache"))

import numpy as np  # noqa: E402
import torch  # noqa: E402
from transformers import Wav2Vec2ForCTC, Wav2Vec2Processor  # noqa: E402

MODEL_ID = "TBOGamer22/wav2vec2-quran-phonetics"
OUT = HERE / "models"
OUT.mkdir(exist_ok=True)
RESULTS = HERE / "results"
RESULTS.mkdir(exist_ok=True)

FP32_PATH = OUT / "w2v2-quran-fp32.onnx"
INT8_PATH = OUT / "w2v2-quran-int8.onnx"

SR = 16000


def mb(path: pathlib.Path) -> float:
    return round(path.stat().st_size / (1024 * 1024), 2)


def main() -> None:
    report: dict = {"step": "01_export_onnx", "model_id": MODEL_ID}

    print(f"[1/5] loading {MODEL_ID} ...")
    t0 = time.time()
    model = Wav2Vec2ForCTC.from_pretrained(MODEL_ID)
    processor = Wav2Vec2Processor.from_pretrained(MODEL_ID)
    model.eval()
    print(f"      loaded in {time.time() - t0:.1f}s")

    n_params = sum(p.numel() for p in model.parameters())
    report["params"] = n_params
    report["params_millions"] = round(n_params / 1e6, 2)
    print(f"      parameters: {n_params:,} ({n_params / 1e6:.2f}M)")

    vocab = processor.tokenizer.get_vocab()
    report["vocab_size"] = len(vocab)
    report["blank_token_id"] = model.config.pad_token_id
    print(f"      vocab size: {len(vocab)}  blank/pad id: {model.config.pad_token_id}")

    # ---- ONNX export (fp32) --------------------------------------------------
    print("[2/5] exporting ONNX (fp32) ...")
    dummy = torch.randn(1, SR * 4, dtype=torch.float32)  # 4s of audio
    t0 = time.time()
    torch.onnx.export(
        model,
        (dummy,),
        str(FP32_PATH),
        input_names=["input_values"],
        output_names=["logits"],
        dynamic_axes={
            "input_values": {0: "batch", 1: "samples"},
            "logits": {0: "batch", 1: "frames"},
        },
        opset_version=17,
        do_constant_folding=True,
        dynamo=False,
    )
    report["export_seconds"] = round(time.time() - t0, 1)
    report["fp32_onnx_mb"] = mb(FP32_PATH)
    print(f"      fp32 ONNX: {report['fp32_onnx_mb']} MB "
          f"({report['export_seconds']}s)")

    # ---- int8 dynamic quantization ------------------------------------------
    print("[3/5] quantizing to int8 (dynamic, weight-only, uint8) ...")
    from onnxruntime.quantization import QuantType, quantize_dynamic

    t0 = time.time()
    quantize_dynamic(
        model_input=str(FP32_PATH),
        model_output=str(INT8_PATH),
        weight_type=QuantType.QUInt8,
        op_types_to_quantize=["MatMul"],
        extra_options={"MatMulConstBOnly": True},
    )
    report["quantize_seconds"] = round(time.time() - t0, 1)
    report["int8_onnx_mb"] = mb(INT8_PATH)
    print(f"      int8 ONNX: {report['int8_onnx_mb']} MB "
          f"({report['quantize_seconds']}s)")

    # ---- G1 ------------------------------------------------------------------
    g1_ceiling = 120.0
    report["G1_ceiling_mb"] = g1_ceiling
    report["G1_measured_mb"] = report["int8_onnx_mb"]
    report["G1_pass"] = report["int8_onnx_mb"] <= g1_ceiling
    report["compression_ratio"] = round(
        report["fp32_onnx_mb"] / report["int8_onnx_mb"], 2
    )
    print(f"[4/5] G1: {report['int8_onnx_mb']} MB vs ceiling {g1_ceiling} MB "
          f"-> {'PASS' if report['G1_pass'] else 'FAIL'}")

    # ---- smoke: both models run and agree in shape ---------------------------
    print("[5/5] smoke test: running both ONNX models on 4s of noise ...")
    import onnxruntime as ort

    audio = np.random.randn(SR * 4).astype(np.float32)
    audio = (audio - audio.mean()) / (audio.std() + 1e-7)
    feed = {"input_values": audio[None, :]}

    so = ort.SessionOptions()
    so.intra_op_num_threads = 1

    out = {}
    for tag, path in (("fp32", FP32_PATH), ("int8", INT8_PATH)):
        sess = ort.InferenceSession(str(path), so, providers=["CPUExecutionProvider"])
        t0 = time.time()
        logits = sess.run(["logits"], feed)[0]
        out[tag] = logits
        print(f"      {tag}: logits {logits.shape} in {time.time() - t0:.2f}s")
        report[f"{tag}_smoke_shape"] = list(logits.shape)
        report[f"{tag}_smoke_seconds"] = round(time.time() - t0, 3)

    report["smoke_shapes_match"] = out["fp32"].shape == out["int8"].shape
    report["frames_per_second"] = round(out["fp32"].shape[1] / 4.0, 2)
    print(f"      frame rate: {report['frames_per_second']} frames/s "
          f"(~{1000 / report['frames_per_second']:.1f} ms/frame)")

    path = RESULTS / "01_export_onnx.json"
    path.write_text(json.dumps(report, indent=2), encoding="utf-8")
    print(f"\nwrote {path}")


if __name__ == "__main__":
    main()
