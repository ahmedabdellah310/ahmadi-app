"""M2.0 - end-to-end ONNX artifact: reflect-padded PCM -> CTC logits.

Composes the conv-basis mel front end (mel_path_spike2, decision PLAN_A2) with
the frozen exp2 encoder + CTC head into one graph. The Kotlin core then does:

    zero-pad the chunk to 15 s -> reflect-pad by n_fft/2 -> one ORT call

and hand-ports no DSP at all, which removes the largest numerical-parity risk
in the port.

The frozen configuration is untouched. This is a packaging change: the same
arithmetic, in one graph instead of two stages.

Correctness is decided by the G2 verification that follows, not by this script.
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
sys.path.insert(0, str(ROOT / "models"))


def _load(name, fn):
    sp = importlib.util.spec_from_file_location(name, SPIKE / fn)
    m = importlib.util.module_from_spec(sp)
    sys.modules[name] = m
    sp.loader.exec_module(m)
    return m


tr = _load("tr", "10_train_exp1.py")
m1 = _load("m1", "20_m1_select.py")
x2 = _load("x2", "23_train_exp2.py")

sp = importlib.util.spec_from_file_location("mp2", ROOT / "models" / "mel_path_spike2.py")
mp2 = importlib.util.module_from_spec(sp)
sys.modules["mp2"] = mp2
sp.loader.exec_module(mp2)

OUT = ROOT / "models" / "out"
REPORT = ROOT / "models" / "export_e2e.json"
N_SAMP = int(m1.WIN_S * m1.SR)          # 240000
N_PADDED = N_SAMP + mp2.N_FFT           # 240400


class EndToEnd(torch.nn.Module):
    def __init__(self, mel, enc, head):
        super().__init__()
        self.mel, self.enc, self.head = mel, enc, head

    def forward(self, pcm_padded):
        f = self.mel(pcm_padded)[:, :, :tr.MEL_FRAMES]
        return self.head(self.enc(f).last_hidden_state)


def sha(p):
    import hashlib
    return hashlib.sha256(p.read_bytes()).hexdigest()


def main():
    import onnxruntime as ort
    from onnxruntime.quantization import QuantType, quantize_dynamic
    from transformers import WhisperFeatureExtractor

    fe = WhisperFeatureExtractor.from_pretrained(tr.BASE)
    filters = torch.tensor(np.asarray(fe.mel_filters).T, dtype=torch.float32)

    ck = torch.load(SPIKE / "models" / "exp2" / "best.pt", map_location="cpu",
                    weights_only=False)
    full = x2.EncCTCBoundary(len(ck["vocab"]))
    full.load_state_dict(ck["model"])
    full.eval()

    model = EndToEnd(mp2.ConvMel(filters), full.enc, full.head).eval()

    dummy = torch.zeros(1, N_PADDED)
    fp32 = OUT / "ratl-align-v1-e2e-fp32.onnx"
    int8 = OUT / "ratl-align-v1-e2e-int8.onnx"
    torch.onnx.export(model, (dummy,), str(fp32), input_names=["pcm_padded"],
                      output_names=["logits"],
                      dynamic_axes={"pcm_padded": {0: "b"}, "logits": {0: "b"}},
                      opset_version=17, do_constant_folding=True, dynamo=False)
    quantize_dynamic(str(fp32), str(int8), weight_type=QuantType.QUInt8,
                     op_types_to_quantize=["MatMul"],
                     extra_options={"MatMulConstBOnly": True})

    import onnx
    ops = sorted({n.op_type for n in onnx.load(str(fp32)).graph.node})

    # sanity: e2e fp32 vs staged (HF mel -> ctc-only graph) on real audio
    import librosa
    so = ort.SessionOptions()
    so.intra_op_num_threads = 1
    se2e = ort.InferenceSession(str(fp32), so, providers=["CPUExecutionProvider"])
    stag = ort.InferenceSession(str(OUT / "ratl-align-v1-fp32.onnx"), so,
                                providers=["CPUExecutionProvider"])

    fe.chunk_length = int(m1.WIN_S)
    fe.n_samples = N_SAMP
    fe.nb_max_frames = tr.MEL_FRAMES
    AUD = SPIKE / ".cache" / "audio"
    probes = [p for p in [AUD / "Husary_64kbps" / "001001.mp3",
                          AUD / "Alafasy_128kbps" / "018001.mp3",
                          AUD / "Abdul_Basit_Mujawwad_128kbps" / "001007.mp3"] if p.exists()]
    ds, agree, ntot = [], 0, 0
    for p in probes:
        a, _ = librosa.load(str(p), sr=m1.SR, mono=True)
        a = a[:N_SAMP]
        if len(a) < N_SAMP:
            a = np.pad(a, (0, N_SAMP - len(a)))
        a = a.astype(np.float32)
        pad = mp2.reflect_pad(a).astype(np.float32)[None]
        f = fe(a, sampling_rate=m1.SR, return_tensors="np", padding="max_length",
               max_length=N_SAMP)["input_features"][0][:, :tr.MEL_FRAMES][None].astype(np.float32)
        y1 = se2e.run(["logits"], {"pcm_padded": pad})[0][0]
        y2 = stag.run(["logits"], {"input_features": f})[0][0]
        ds.append(float(np.abs(y1 - y2).max()))
        agree += int((y1.argmax(-1) == y2.argmax(-1)).sum())
        ntot += y1.shape[0]

    rep = {"step": "M2.0 export_e2e",
           "graph": "reflect-padded PCM (1, %d) -> logits (1, %d, %d)" % (
               N_PADDED, tr.ENC_FRAMES, len(ck["vocab"])),
           "mel_frontend": "conv-basis DFT (PLAN_A2)",
           "boundary_head_dropped": True,
           "op_types": ops,
           "sizes_mb": {"fp32": round(fp32.stat().st_size / 1048576, 4),
                        "int8": round(int8.stat().st_size / 1048576, 4)},
           "e2e_vs_staged_fp32": {"max_abs": round(max(ds), 8),
                                  "argmax_agreement": round(agree / ntot, 6),
                                  "frames": ntot},
           "artifacts": {"fp32": {"name": fp32.name, "sha256": sha(fp32)},
                         "int8": {"name": int8.name, "sha256": sha(int8)}},
           "note": "Correctness is decided by verify_onnx_g2_e2e.py, not by this file."}
    REPORT.write_text(json.dumps(rep, indent=2), encoding="utf-8")
    print(json.dumps(rep, indent=2))


if __name__ == "__main__":
    main()
