"""M2.0 - decide the mel front-end implementation path.

Plan A: STFT + mel + Whisper log-scaling live INSIDE the ONNX graph. The Kotlin
        side then hands raw PCM to ONNX Runtime and no DSP is hand-ported at
        all, so feature parity is guaranteed by construction.
Plan B: the front end is ported to Kotlin, with the 80x201 mel filterbank
        shipped as a generated asset rather than recomputed, so only the STFT is
        hand-written.

This script measures whether Plan A is actually available: does torch export it
at opset 17, does ONNX Runtime execute it, and does it reproduce
WhisperFeatureExtractor to within the T1 conformance tolerance (1e-3)?

Decision is made on measurement, not preference. No frozen behaviour changes
either way - both plans must produce the same features the frozen pipeline used.
"""
import importlib.util
import json
import pathlib
import sys

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
REPORT = ROOT / "models" / "mel_path_spike.json"
N_FFT, HOP = 400, 160
T1_TOL = 1e-3


class WhisperMel(torch.nn.Module):
    """Whisper log-mel, expressed for ONNX export.

    Mirrors transformers WhisperFeatureExtractor._torch_extract_fbank_features:
    reflect-padded STFT, power spectrum with the final frame dropped, mel
    projection, log10, dynamic-range clamp at (max - 8), then (x + 4) / 4.
    """

    def __init__(self, filters, window):
        super().__init__()
        self.register_buffer("filters", filters)
        self.register_buffer("window", window)

    def forward(self, pcm):                      # pcm: (B, n_samples)
        spec = torch.stft(pcm, N_FFT, HOP, window=self.window,
                          center=True, pad_mode="reflect", return_complex=True)
        mag = (spec[..., :-1].abs() ** 2)        # (B, 201, T)
        mel = self.filters @ mag                 # (B, 80, T)
        log = torch.log10(torch.clamp(mel, min=1e-10))
        log = torch.maximum(log, log.amax(dim=(-2, -1), keepdim=True) - 8.0)
        return (log + 4.0) / 4.0


def main():
    import onnxruntime as ort
    from transformers import WhisperFeatureExtractor

    fe = WhisperFeatureExtractor.from_pretrained(tr.BASE)
    fe.chunk_length = int(m1.WIN_S)
    fe.n_samples = int(m1.WIN_S * m1.SR)
    fe.nb_max_frames = tr.MEL_FRAMES

    filters = torch.tensor(np.asarray(fe.mel_filters).T, dtype=torch.float32)
    window = torch.hann_window(N_FFT)
    rep = {"step": "M2.0 mel_path_spike",
           "filterbank_shape": list(filters.shape),
           "n_fft": N_FFT, "hop": HOP, "T1_tolerance": T1_TOL}

    mod = WhisperMel(filters, window).eval()

    # ---- torch module vs HF reference, on real audio
    import librosa
    AUD = SPIKE / ".cache" / "audio"
    probes = [AUD / "Husary_64kbps" / "001001.mp3",
              AUD / "Alafasy_128kbps" / "018001.mp3",
              AUD / "Abdul_Basit_Mujawwad_128kbps" / "001007.mp3"]
    probes = [p for p in probes if p.exists()]
    n_samp = int(m1.WIN_S * m1.SR)

    def hf(ch):
        f = fe(ch, sampling_rate=m1.SR, return_tensors="np", padding="max_length",
               max_length=n_samp)["input_features"][0][:, :tr.MEL_FRAMES]
        if f.shape[1] < tr.MEL_FRAMES:
            f = np.pad(f, ((0, 0), (0, tr.MEL_FRAMES - f.shape[1])), constant_values=f.min())
        return f

    pcms, refs = [], []
    for p in probes:
        a, _ = librosa.load(str(p), sr=m1.SR, mono=True)
        a = a[:n_samp]
        if len(a) < n_samp:
            a = np.pad(a, (0, n_samp - len(a)))
        pcms.append(a.astype(np.float32))
        refs.append(hf(a))

    d_torch = []
    for a, r in zip(pcms, refs):
        with torch.no_grad():
            y = mod(torch.from_numpy(a)[None])[0].numpy()[:, :tr.MEL_FRAMES]
        d_torch.append(float(np.abs(y - r).max()))
    rep["torch_module_vs_hf_max_abs"] = round(max(d_torch), 8)

    # ---- Plan A: export and run in ONNX Runtime
    path = OUT / "mel-frontend.onnx"
    planA = {"exported": False, "ort_loads": False, "ort_runs": False,
             "max_abs_vs_hf": None, "error": None}
    try:
        torch.onnx.export(mod, (torch.from_numpy(pcms[0])[None],), str(path),
                          input_names=["pcm"], output_names=["mel"],
                          dynamic_axes={"pcm": {0: "b"}, "mel": {0: "b"}},
                          opset_version=17, do_constant_folding=True, dynamo=False)
        planA["exported"] = path.exists()
        planA["size_mb"] = round(path.stat().st_size / 1048576, 4)
        so = ort.SessionOptions()
        so.intra_op_num_threads = 1
        sess = ort.InferenceSession(str(path), so, providers=["CPUExecutionProvider"])
        planA["ort_loads"] = True
        ds = []
        for a, r in zip(pcms, refs):
            y = sess.run(["mel"], {"pcm": a[None]})[0][0][:, :tr.MEL_FRAMES]
            ds.append(float(np.abs(y - r).max()))
        planA["ort_runs"] = True
        planA["max_abs_vs_hf"] = round(max(ds), 8)
        planA["within_T1"] = bool(max(ds) <= T1_TOL)
    except Exception as e:  # noqa: BLE001
        planA["error"] = f"{type(e).__name__}: {e}"[:400]
    rep["plan_A_onnx_frontend"] = planA

    # ---- Plan B feasibility: the filterbank ships as an asset
    rep["plan_B_kotlin_frontend"] = {
        "filterbank_asset": "80x201 float32 = %d bytes, generated not recomputed"
                            % (80 * 201 * 4),
        "hand_ported": "reflect pad, hann window, real FFT (n=400), power spec, "
                       "matmul, log10, dynamic clamp, affine",
        "guarded_by": "T1 conformance vector, tolerance 1e-3",
    }

    ok = planA["ort_runs"] and planA.get("within_T1")
    rep["decision"] = "PLAN_A" if ok else "PLAN_B"
    rep["decision_basis"] = (
        "ONNX front end loads, runs in ORT, and matches WhisperFeatureExtractor "
        "within the T1 tolerance." if ok else
        "Plan A unavailable or outside tolerance; the front end is ported to "
        "Kotlin with the filterbank shipped as a generated asset.")
    REPORT.write_text(json.dumps(rep, indent=2), encoding="utf-8")
    print(json.dumps(rep, indent=2))


if __name__ == "__main__":
    main()
