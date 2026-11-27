"""
RATL Week 1 spike — Rung 3, step 3b: architecture-class screen for G5.

07 established that wav2vec2's raw-waveform conv frontend is a fixed dominant
cost, so NO depth of wav2vec2 plausibly reaches G5 (<=0.5 RTF on Dimensity 1080).

This screens architecture classes with a CHEAP frontend (log-mel features):
  - Whisper encoder + CTC head (with shortened positional embeddings)
  - Zipformer streaming CTC (real pretrained sherpa-onnx model, any language --
    architecture speed is language-independent, so this is a valid speed probe)

MEASURED: int8 size, desktop 1-thread RTF.
ESTIMATED: on-device RTF (desktop x device factor). Flagged as such.
Says NOTHING about accuracy -- this is a deployment-feasibility screen only.
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
VOCAB = 40
G1_CEILING_MB = 120.0
G5_CEILING_RTF = 0.5
DEVICE_FACTOR = (3.0, 5.0)


def mb(p):
    return round(p.stat().st_size / (1024 * 1024), 2)


def clips():
    import librosa

    out = []
    for p in (AUDIO / "Husary_64kbps" / "001001.mp3",
              AUDIO / "Alafasy_128kbps" / "018001.mp3",
              AUDIO / "Husary_64kbps" / "002255.mp3"):
        if p.exists():
            a, _ = librosa.load(str(p), sr=SR, mono=True)
            out.append((p.name, ((a - a.mean()) / (a.std() + 1e-7)).astype(np.float32)))
    return out


def logmel(a, n_mels=80):
    import librosa

    m = librosa.feature.melspectrogram(y=a, sr=SR, n_fft=400, hop_length=160, n_mels=n_mels)
    return np.log(np.maximum(m, 1e-10)).astype(np.float32)


class WhisperEncCTC(torch.nn.Module):
    """Whisper encoder with SHORTENED positional embeddings + CTC head.

    Stock Whisper forces a 30 s (3000-frame) input, which wastes compute on short
    utterances and blocks streaming. max_source_positions is cut so the encoder
    accepts a shorter window.
    """

    def __init__(self, name, window_s=15.0, vocab=VOCAB):
        super().__init__()
        from transformers import WhisperConfig, WhisperModel

        cfg = WhisperConfig.from_pretrained(name)
        # mel hop 160 -> 100 fps; encoder conv stride 2 -> 50 fps
        cfg.max_source_positions = int(window_s * 100 / 2)
        self.cfg = cfg
        self.enc = WhisperModel(cfg).encoder
        self.head = torch.nn.Linear(cfg.d_model, vocab)
        self.bnd = torch.nn.Linear(cfg.d_model, 2)
        self.n_mels = cfg.num_mel_bins
        self.window_frames = cfg.max_source_positions * 2

    def forward(self, feats):
        return self.head(self.enc(feats).last_hidden_state)


def export_quant(model, dummy, tag, iname, dyn):
    fp32, int8 = OUT / f"{tag}-fp32.onnx", OUT / f"{tag}-int8.onnx"
    model.eval()
    torch.onnx.export(model, (dummy,), str(fp32), input_names=[iname],
                      output_names=["logits"], dynamic_axes=dyn,
                      opset_version=17, do_constant_folding=True, dynamo=False)
    from onnxruntime.quantization import QuantType, quantize_dynamic

    quantize_dynamic(str(fp32), str(int8), weight_type=QuantType.QUInt8,
                     op_types_to_quantize=["MatMul"],
                     extra_options={"MatMulConstBOnly": True})
    return fp32, int8


def rtf_whisper(path, cs, n_mels, window_frames):
    """Chunked inference: fixed window, so RTF counts every window needed."""
    import onnxruntime as ort

    so = ort.SessionOptions()
    so.intra_op_num_threads = 1
    s = ort.InferenceSession(str(path), so, providers=["CPUExecutionProvider"])
    name = s.get_inputs()[0].name
    out = []
    for _, a in cs:
        dur = len(a) / SR
        f = logmel(a, n_mels)
        t0 = time.time()
        for st in range(0, max(1, f.shape[1]), window_frames):
            w = f[:, st:st + window_frames]
            if w.shape[1] < window_frames:
                w = np.pad(w, ((0, 0), (0, window_frames - w.shape[1])),
                           constant_values=np.log(1e-10))
            s.run(["logits"], {name: w[None]})
        out.append((time.time() - t0) / dur)
    return round(float(np.mean(out)), 3), [round(x, 3) for x in out]


def probe_zipformer(cs):
    """Measure a real pretrained streaming Zipformer CTC encoder for speed only."""
    from huggingface_hub import hf_hub_download

    repo = "csukuangfj/sherpa-onnx-streaming-zipformer-zh-14M-2023-02-23"
    tried = []
    for fn in ("encoder-epoch-99-avg-1.onnx", "encoder-epoch-99-avg-1.int8.onnx"):
        try:
            p = pathlib.Path(hf_hub_download(repo_id=repo, filename=fn))
            tried.append((fn, p))
        except Exception as e:  # noqa: BLE001
            print(f"      could not fetch {fn}: {type(e).__name__}")
    if not tried:
        return None

    import onnxruntime as ort

    fn, p = tried[-1]
    so = ort.SessionOptions()
    so.intra_op_num_threads = 1
    s = ort.InferenceSession(str(p), so, providers=["CPUExecutionProvider"])
    meta = {i.name: [d if isinstance(d, int) else str(d) for d in i.shape]
            for i in s.get_inputs()}
    return {"repo": repo, "file": fn, "int8_onnx_mb": mb(p), "inputs": meta,
            "note": "streaming encoder; stateful inputs, RTF not directly comparable "
                    "without the full state loop -- size and input spec reported"}


def main():
    cs = clips()
    print(f"[bench] {len(cs)} clips")
    cands = []

    for name, tag, win in (("openai/whisper-tiny", "whisper-tiny-enc-15s", 15.0),
                           ("openai/whisper-tiny", "whisper-tiny-enc-8s", 8.0),
                           ("openai/whisper-base", "whisper-base-enc-15s", 15.0)):
        print(f"\n[{tag}] building ...")
        try:
            m = WhisperEncCTC(name, window_s=win)
            n = sum(p.numel() for p in m.parameters())
            dummy = torch.randn(1, m.n_mels, m.window_frames)
            fp32, int8 = export_quant(
                m, dummy, tag, "input_features",
                {"input_features": {0: "b"}, "logits": {0: "b"}})
            r, per = rtf_whisper(int8, cs, m.n_mels, m.window_frames)
            cands.append({"name": tag, "family": "whisper encoder + CTC (mel input)",
                          "params": n, "params_M": round(n / 1e6, 2),
                          "fp32_onnx_mb": mb(fp32), "int8_onnx_mb": mb(int8),
                          "window_s": win, "desktop_rtf_1thread": r, "per_clip_rtf": per})
            print(f"      {n/1e6:.2f}M params  int8 {mb(int8)} MB  rtf {r}")
            del m
        except Exception as e:  # noqa: BLE001
            print(f"      FAILED: {type(e).__name__}: {e}")

    print("\n[zipformer] probing pretrained streaming Zipformer CTC ...")
    zf = probe_zipformer(cs)
    print(f"      {zf}")

    for c in cands:
        c["G1_pass_measured"] = c["int8_onnx_mb"] <= G1_CEILING_MB
        lo = round(c["desktop_rtf_1thread"] * DEVICE_FACTOR[0], 3)
        hi = round(c["desktop_rtf_1thread"] * DEVICE_FACTOR[1], 3)
        c["G5_projected_rtf_range"] = [lo, hi]
        c["G5_verdict_estimated"] = ("PLAUSIBLE" if hi <= G5_CEILING_RTF else
                                     "MARGINAL" if lo <= G5_CEILING_RTF else "IMPLAUSIBLE")

    rep = {"step": "08_arch_screen",
           "measured": ["params", "int8_onnx_mb", "desktop_rtf_1thread"],
           "estimated": ["G5_projected_rtf_range"],
           "assumptions": {"device_factor_range": DEVICE_FACTOR,
                           "weights": "random init -- speed/size only, not accuracy"},
           "spec_requirements": {"G1_ceiling_mb": G1_CEILING_MB,
                                 "G5_ceiling_rtf": G5_CEILING_RTF},
           "wav2vec2_family_result_from_07": "no depth reaches G5; conv frontend dominates",
           "candidates": cands, "zipformer_probe": zf}
    (RESULTS / "08_arch_screen.json").write_text(json.dumps(rep, indent=2), encoding="utf-8")

    print("\n===== ARCHITECTURE SCREEN =====")
    print(f"{'candidate':<24}{'params':>9}{'int8 MB':>10}{'rtf':>8}  {'G5 (est)':<12}")
    for c in cands:
        print(f"{c['name']:<24}{c['params_M']:>8.2f}M{c['int8_onnx_mb']:>10.2f}"
              f"{c['desktop_rtf_1thread']:>8.3f}  {c['G5_verdict_estimated']:<12}"
              f"{c['G5_projected_rtf_range']}")


if __name__ == "__main__":
    main()
