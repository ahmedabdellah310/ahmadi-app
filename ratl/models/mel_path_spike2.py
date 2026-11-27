"""M2.0 - Plan A, second attempt: the DFT expressed as a convolution.

torch.onnx.export refuses torch.stft ("STFT does not currently support complex
types"). But an STFT with a fixed window and fixed frame size is a linear map,
so it can be written as a Conv1d against a precomputed cos/sin basis. That
exports to Conv, Mul, Add, ReduceMax, Log - all core ONNX ops with far broader
mobile coverage than the opset-17 STFT operator would have had.

The reflect padding is done once, outside the graph, so the graph itself is
plain convolution arithmetic.

If this matches WhisperFeatureExtractor within the T1 tolerance it is strictly
better than Plan B: the Kotlin side hands over PCM and hand-ports no DSP.
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

OUT = ROOT / "models" / "out"
REPORT = ROOT / "models" / "mel_path_spike2.json"
N_FFT, HOP, N_BINS = 400, 160, 201
T1_TOL = 1e-3


class ConvMel(torch.nn.Module):
    """Whisper log-mel with the DFT as a convolution. Input is REFLECT-PADDED pcm."""

    def __init__(self, filters):
        super().__init__()
        w = torch.hann_window(N_FFT, periodic=True, dtype=torch.float64)
        k = torch.arange(N_BINS, dtype=torch.float64)[:, None]
        n = torch.arange(N_FFT, dtype=torch.float64)[None, :]
        ang = 2.0 * np.pi * k * n / N_FFT
        basis = torch.cat([torch.cos(ang) * w, -torch.sin(ang) * w], 0)  # (402, 400)
        self.register_buffer("basis", basis[:, None, :].float())
        self.register_buffer("filters", filters)

    def forward(self, padded):                   # (B, n_samples + N_FFT)
        f = torch.nn.functional.conv1d(padded[:, None, :], self.basis, stride=HOP)
        re, im = f[:, :N_BINS], f[:, N_BINS:]
        mag = (re * re + im * im)[..., :-1]       # power spectrum, drop last frame
        mel = torch.matmul(self.filters, mag)
        log = torch.log10(torch.clamp(mel, min=1e-10))
        log = torch.maximum(log, log.amax(dim=(-2, -1), keepdim=True) - 8.0)
        return (log + 4.0) / 4.0


def reflect_pad(a):
    """center=True padding, done once outside the graph."""
    p = N_FFT // 2
    return np.concatenate([a[p:0:-1], a, a[-2:-p - 2:-1]])


def main():
    import librosa
    import onnxruntime as ort
    from transformers import WhisperFeatureExtractor

    fe = WhisperFeatureExtractor.from_pretrained(tr.BASE)
    fe.chunk_length = int(m1.WIN_S)
    fe.n_samples = int(m1.WIN_S * m1.SR)
    fe.nb_max_frames = tr.MEL_FRAMES

    filters = torch.tensor(np.asarray(fe.mel_filters).T, dtype=torch.float32)
    mod = ConvMel(filters).eval()
    n_samp = int(m1.WIN_S * m1.SR)

    AUD = SPIKE / ".cache" / "audio"
    probes = [p for p in [AUD / "Husary_64kbps" / "001001.mp3",
                          AUD / "Alafasy_128kbps" / "018001.mp3",
                          AUD / "Abdul_Basit_Mujawwad_128kbps" / "001007.mp3",
                          AUD / "Saood_ash-Shuraym_128kbps" / "036001.mp3"] if p.exists()]

    def hf(ch):
        f = fe(ch, sampling_rate=m1.SR, return_tensors="np", padding="max_length",
               max_length=n_samp)["input_features"][0][:, :tr.MEL_FRAMES]
        if f.shape[1] < tr.MEL_FRAMES:
            f = np.pad(f, ((0, 0), (0, tr.MEL_FRAMES - f.shape[1])), constant_values=f.min())
        return f

    pcms, pads, refs = [], [], []
    for p in probes:
        a, _ = librosa.load(str(p), sr=m1.SR, mono=True)
        a = a[:n_samp]
        if len(a) < n_samp:
            a = np.pad(a, (0, n_samp - len(a)))
        a = a.astype(np.float32)
        pcms.append(a)
        pads.append(reflect_pad(a).astype(np.float32))
        refs.append(hf(a))

    rep = {"step": "M2.0 mel_path_spike2 (conv-basis DFT)",
           "T1_tolerance": T1_TOL, "ops": "Conv, Mul, Add, MatMul, Clip, Log, ReduceMax"}

    dt = []
    for pa, r in zip(pads, refs):
        with torch.no_grad():
            y = mod(torch.from_numpy(pa)[None])[0].numpy()[:, :tr.MEL_FRAMES]
        dt.append(float(np.abs(y - r).max()))
    rep["torch_convmel_vs_hf_max_abs"] = round(max(dt), 8)

    path = OUT / "mel-frontend-conv.onnx"
    res = {"exported": False, "ort_loads": False, "ort_runs": False, "error": None}
    try:
        torch.onnx.export(mod, (torch.from_numpy(pads[0])[None],), str(path),
                          input_names=["pcm_padded"], output_names=["mel"],
                          dynamic_axes={"pcm_padded": {0: "b"}, "mel": {0: "b"}},
                          opset_version=17, do_constant_folding=True, dynamo=False)
        res["exported"] = True
        res["size_mb"] = round(path.stat().st_size / 1048576, 4)
        so = ort.SessionOptions()
        so.intra_op_num_threads = 1
        sess = ort.InferenceSession(str(path), so, providers=["CPUExecutionProvider"])
        res["ort_loads"] = True
        ds = []
        for pa, r in zip(pads, refs):
            y = sess.run(["mel"], {"pcm_padded": pa[None]})[0][0][:, :tr.MEL_FRAMES]
            ds.append(float(np.abs(y - r).max()))
        res["ort_runs"] = True
        res["max_abs_vs_hf"] = round(max(ds), 8)
        res["within_T1"] = bool(max(ds) <= T1_TOL)

        x = pads[0][None]
        sess.run(["mel"], {"pcm_padded": x})
        t0 = time.perf_counter()
        for _ in range(20):
            sess.run(["mel"], {"pcm_padded": x})
        res["ms_per_15s_window"] = round((time.perf_counter() - t0) / 20 * 1000, 3)

        import onnx
        res["op_types"] = sorted({n.op_type for n in onnx.load(str(path)).graph.node})
    except Exception as e:  # noqa: BLE001
        res["error"] = f"{type(e).__name__}: {e}"[:300]
    rep["plan_A2_conv_frontend"] = res

    ok = res.get("ort_runs") and res.get("within_T1")
    rep["decision"] = "PLAN_A2" if ok else "PLAN_B"
    rep["decision_basis"] = (
        "Conv-basis DFT front end exports, runs in ONNX Runtime and matches "
        "WhisperFeatureExtractor within the T1 tolerance using only core ops. "
        "The Kotlin side ports no DSP." if ok else
        "Conv front end unavailable or out of tolerance; port the front end to "
        "Kotlin with the mel filterbank shipped as a generated asset.")
    REPORT.write_text(json.dumps(rep, indent=2), encoding="utf-8")
    print(json.dumps(rep, indent=2))


if __name__ == "__main__":
    main()
