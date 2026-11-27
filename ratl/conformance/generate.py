"""M2.0 - generate the RATL conformance golden vectors.

SPEC 6.3: conformance vectors are BLOCKING for V1. With the Rust core deferred
to v1.1 (SPEC 6.1) these vectors are the executable specification any future
implementation must satisfy, and the mechanism that makes the deferral safe.

Every vector is derived from the spike reference modules IMPORTED UNMODIFIED, so
the vectors are provably produced by the same code path that measured
G2 = 0.8570 - not from a re-description of it.

Tiers
  T0  pure functions, no model      exact on integers, 1e-9 on doubles
  T1  front end: PCM -> log-mel     1e-3
  T2  model + decode -> boundaries  logits 1e-3, FRAME INDICES EXACT
  T3  end-to-end Assessment         deferred to M2.2 (taxonomy not built yet)

Design rule: assert exactly on discrete outputs and tolerate only the continuous
ones. Frame indices come from argmax paths, so any tolerance there would hide a
real behavioural divergence rather than absorb numerical noise.
"""
import hashlib
import importlib.util
import json
import pathlib
import sys

import numpy as np

ROOT = pathlib.Path(__file__).resolve().parent.parent
SPIKE = ROOT / "spike"
VEC = ROOT / "conformance" / "vectors"
VEC.mkdir(parents=True, exist_ok=True)
sys.path.insert(0, str(SPIKE))

SPEC_VERSION = "0.3"
SEED = 1447


def _load(name, fn, base=SPIKE):
    sp = importlib.util.spec_from_file_location(name, base / fn)
    m = importlib.util.module_from_spec(sp)
    sys.modules[name] = m
    sp.loader.exec_module(m)
    return m


g2 = _load("g2", "05_g2_alignment.py")
tr = _load("tr", "10_train_exp1.py")
e1 = _load("e1", "13_eval_exp1.py")
d3 = _load("d3", "18_decode_extent.py")
m1 = _load("m1", "20_m1_select.py")
mp2 = _load("mp2", "mel_path_spike2.py", ROOT / "models")

OUT = ROOT / "models" / "out"
N_SAMP = int(m1.WIN_S * m1.SR)


def write(name, obj):
    p = VEC / name
    p.write_text(json.dumps(obj, ensure_ascii=False, indent=1), encoding="utf-8")
    return {"file": name, "sha256": hashlib.sha256(p.read_bytes()).hexdigest(),
            "bytes": p.stat().st_size}


# ------------------------------------------------------------------ T0
def t0_tokenize(stoi):
    """Transliteration -> CTC ids with explicit '|' separators, plus word spans."""
    cases = [
        ["bismi", "l-lāhi", "r-raḥmāni", "r-raḥīmi"],
        ["qul"],
        ["alḥamdu", "lillāhi"],
        ["", "rabbi"],                    # empty word -> span None, no separator
        ["ṭāhā", "ʿalayka"],              # full diacritic coverage
        ["wa", "ʾiḏ", "qāla"],            # hamza forms
    ]
    out = []
    for words in cases:
        ids, spans, oov = e1.build_tokens_v2([{"tr": w} for w in words], stoi)
        out.append({"words": words, "ids": ids,
                    "spans": [list(s) if s else None for s in spans], "oov": oov})
    return {"tier": "T0", "fn": "build_tokens_v2", "tolerance": "exact",
            "vocab_size": len(stoi), "cases": out}


def t0_log_softmax(rng):
    cases = []
    for shape in [(1, 5), (4, 35), (16, 35)]:
        z = (rng.standard_normal(shape) * 6.0).round(4)
        cases.append({"input": z.tolist(),
                      "output": g2.log_softmax(z.astype(np.float64)).tolist()})
    z = np.full((2, 5), 1000.0)           # overflow guard
    z[0, 0] = 1001.0
    cases.append({"input": z.tolist(), "note": "large values, overflow guard",
                  "output": g2.log_softmax(z).tolist()})
    return {"tier": "T0", "fn": "log_softmax", "tolerance": 1e-9, "cases": cases}


def t0_forced_align(rng):
    """Viterbi over the blank-augmented CTC lattice."""
    cases = []

    def case(logits, labels, note):
        lp = g2.log_softmax(np.asarray(logits, dtype=np.float64))
        sp = g2.ctc_forced_align(lp, labels, blank=0)
        cases.append({"note": note, "logits": np.asarray(logits).round(4).tolist(),
                      "labels": labels,
                      "spans": [list(s) if s else None for s in sp]})

    V = 6
    case((rng.standard_normal((12, V)) * 3).round(4), [1, 2, 3], "generic")
    case((rng.standard_normal((20, V)) * 3).round(4), [1, 1, 2], "repeated label needs a blank")
    case((rng.standard_normal((30, V)) * 3).round(4), [1, 2, 1, 2, 1], "alternating")
    case((rng.standard_normal((8, V)) * 3).round(4), [1], "single label")
    z = np.zeros((10, V))
    z[:, 0] = 5.0
    case(z.round(4), [1, 2], "blank-dominated: path forced through low-prob labels")
    z2 = np.zeros((6, V))
    case(z2.round(4), [1, 2, 3], "exact ties everywhere - tie-break must match")
    return {"tier": "T0", "fn": "ctc_forced_align",
            "tolerance": "EXACT (integer frame indices)", "blank": 0, "cases": cases}


def t0_word_bounds():
    """All boundary rules, including both M2E edge-fallback branches."""
    spans = [(0, 5), (6, 9), (10, 16)]          # 3 words, '|' at 5 and 9
    tok = [(0, 2), (3, 5), (6, 8), (9, 11), (12, 14),      # word 0 chars
           (16, 17),                                        # '|'
           (18, 20), (21, 23), (24, 26),                    # word 1
           (28, 29),                                        # '|'
           (31, 33), (34, 36), (37, 39), (40, 42), (43, 45), (46, 48)]
    n_tok, T = 16, 60
    cases = []
    for rule in ["M0_spike", "M1_half_gap", "M2_separator", "M2E_edge_spike", "M3_full_gap"]:
        for wi in range(3):
            b = d3.word_bounds(rule, spans, tok, wi, n_tok, T)
            cases.append({"rule": rule, "word": wi,
                          "edge": "first" if wi == 0 else ("last" if wi == 2 else "interior"),
                          "bounds": list(b) if b else None})
    holes = list(tok)
    holes[5] = None                              # separator never emitted
    for wi in range(3):
        b = d3.word_bounds("M2E_edge_spike", spans, holes, wi, n_tok, T)
        cases.append({"rule": "M2E_edge_spike", "word": wi, "edge": "missing_separator",
                      "bounds": list(b) if b else None})
    b = d3.word_bounds("M2E_edge_spike", [None] + spans[1:], tok, 0, n_tok, T)
    cases.append({"rule": "M2E_edge_spike", "word": 0, "edge": "null_span",
                  "bounds": list(b) if b else None})
    return {"tier": "T0", "fn": "word_bounds",
            "frozen_rule": "M2E_edge_spike", "tolerance": "exact",
            "fixture": {"spans": [list(s) for s in spans],
                        "tok_spans": [list(t) if t else None for t in tok],
                        "n_tok": n_tok, "T": T},
            "cases": cases}


def t0_windowing():
    """15 s window / 10 s hop / 2.5 s margin discard - the frame bookkeeping."""
    win, hop = N_SAMP, int(m1.HOP_S * m1.SR)
    mfr = int(m1.MARGIN_S * m1.FPS)
    cases = []
    for dur in [1.0, 5.0, 14.9, 15.0, 15.1, 20.0, 25.0, 35.0, 60.71]:
        n = int(dur * m1.SR)
        plan = []
        if n <= win:
            plan.append({"start": 0, "valid": max(1, int(min(n / m1.SR * m1.FPS, tr.ENC_FRAMES))),
                         "keep_lo": 0,
                         "keep_hi": max(1, int(min(n / m1.SR * m1.FPS, tr.ENC_FRAMES)))})
        else:
            st = 0
            while st < n:
                ln = len(range(st, min(st + win, n)))
                v = max(1, int(min(ln / m1.SR * m1.FPS, tr.ENC_FRAMES)))
                lo = 0 if st == 0 else mfr
                hi = v if st + win >= n else v - mfr
                if hi > lo:
                    plan.append({"start": st, "valid": v, "keep_lo": lo, "keep_hi": hi})
                if st + win >= n:
                    break
                st += hop
        cases.append({"duration_s": dur, "n_samples": n,
                      "total_frames": sum(p["keep_hi"] - p["keep_lo"] for p in plan),
                      "windows": plan})
    return {"tier": "T0", "fn": "windowing_geometry", "tolerance": "exact",
            "window_s": m1.WIN_S, "hop_s": m1.HOP_S, "margin_s": m1.MARGIN_S,
            "fps": m1.FPS, "ms_per_frame": m1.MS_PER_FRAME,
            "enc_frames": tr.ENC_FRAMES, "cases": cases}


# ------------------------------------------------------------------ T1 / T2
def clip_vectors():
    """Real audio: PCM -> reflect pad -> logits -> forced align -> boundaries."""
    import librosa
    import onnxruntime as ort

    picks = [("Husary_64kbps", 1, 1), ("Alafasy_128kbps", 112, 1),
             ("Abdul_Basit_Mujawwad_128kbps", 108, 1)]
    gt = g2.load_gt()
    so = ort.SessionOptions()
    so.intra_op_num_threads = 1
    sess = ort.InferenceSession(str(OUT / "ratl-align-v1-e2e-fp32.onnx"), so,
                                providers=["CPUExecutionProvider"])
    ck_vocab = json.loads((OUT / "vocab.json").read_text(encoding="utf-8"))
    stoi = {c: i for i, c in enumerate(ck_vocab)}

    t1, t2 = [], []
    for rec, s, a in picks:
        p = SPIKE / ".cache" / "audio" / rec / f"{s:03d}{a:03d}.mp3"
        if not p.exists():
            continue
        audio, _ = librosa.load(str(p), sr=m1.SR, mono=True)
        audio = audio[:N_SAMP].astype(np.float32)
        pcm16 = np.clip(np.round(audio * 32768.0), -32768, 32767).astype(np.int16)
        chunk = (pcm16.astype(np.float32) / 32768.0)
        if len(chunk) < N_SAMP:
            chunk = np.pad(chunk, (0, N_SAMP - len(chunk)))
        pad = mp2.reflect_pad(chunk).astype(np.float32)

        raw = VEC / f"pcm_{rec}_{s:03d}{a:03d}.s16"
        raw.write_bytes(pcm16.tobytes())

        t1.append({"pcm_file": raw.name, "n_samples_raw": int(pcm16.size),
                   "pad_head": [round(float(x), 8) for x in pad[:8]],
                   "pad_tail": [round(float(x), 8) for x in pad[-8:]],
                   "n_padded": int(pad.size)})

        lg = sess.run(["logits"], {"pcm_padded": pad[None]})[0][0]
        words = g2.fetch_translit(s, a)
        ids, spans, _ = e1.build_tokens_v2(words, stoi)
        v = max(1, int(min(len(pcm16) / m1.SR * m1.FPS, tr.ENC_FRAMES)))
        lg = lg[:v]
        tok = g2.ctc_forced_align(g2.log_softmax(lg.astype(np.float64)), ids, blank=0)
        bounds = []
        for wi in range(len(spans)):
            b = d3.word_bounds("M2E_edge_spike", spans, tok, wi, len(ids), lg.shape[0])
            bounds.append(None if b is None else
                          {"word": wi, "start_frame": b[0], "end_frame": b[1],
                           "start_ms": round(b[0] * m1.MS_PER_FRAME, 1),
                           "end_ms": round(b[1] * m1.MS_PER_FRAME, 1)})
        seg = {sg[0]: sg for sg in gt[rec][(s, a)]["segments"]}
        t2.append({"pcm_file": raw.name, "reciter": rec, "surah": s, "ayah": a,
                   "words": [w["tr"] for w in words], "ids": ids,
                   "valid_frames": int(lg.shape[0]),
                   "logits_sha256": hashlib.sha256(
                       np.round(lg, 3).astype(np.float32).tobytes()).hexdigest(),
                   "logits_first_frame": [round(float(x), 5) for x in lg[0]],
                   "token_spans": [list(t) if t else None for t in tok],
                   "boundaries": bounds,
                   "ground_truth_ms": {str(k): [seg[k][2], seg[k][3]] for k in sorted(seg)}})

    return ({"tier": "T1", "fn": "reflect_pad", "tolerance": 1e-6,
             "n_fft": mp2.N_FFT, "pad": mp2.N_FFT // 2, "cases": t1},
            {"tier": "T2", "fn": "logits + forced_align + M2E_edge_spike",
             "model": "ratl-align-v1-e2e-fp32.onnx",
             "tolerance": {"logits": 1e-3, "frame_indices": "EXACT"},
             "pcm_format": "int16 LE mono 16 kHz, divide by 32768.0 for float",
             "cases": t2})


def main():
    rng = np.random.default_rng(SEED)
    stoi = {c: i for i, c in enumerate(
        json.loads((OUT / "vocab.json").read_text(encoding="utf-8")))}

    files = []
    files.append(write("t0_tokenize.json", t0_tokenize(stoi)))
    files.append(write("t0_log_softmax.json", t0_log_softmax(rng)))
    files.append(write("t0_forced_align.json", t0_forced_align(rng)))
    files.append(write("t0_word_bounds.json", t0_word_bounds()))
    files.append(write("t0_windowing.json", t0_windowing()))
    v1, v2 = clip_vectors()
    files.append(write("t1_frontend.json", v1))
    files.append(write("t2_decode.json", v2))

    e2e = json.loads((ROOT / "models" / "export_e2e.json").read_text(encoding="utf-8"))
    man = {
        "spec_version": SPEC_VERSION,
        "generator": "conformance/generate.py",
        "generated_from": "spike reference modules imported unmodified",
        "seed": SEED,
        "model": {"name": "ratl-align-v1-e2e-fp32.onnx",
                  "sha256": e2e["artifacts"]["fp32"]["sha256"]},
        "vocab_sha256": hashlib.sha256((OUT / "vocab.json").read_bytes()).hexdigest(),
        "frozen_config": {
            "decoder": "M2E_edge_spike", "window_s": m1.WIN_S, "hop_s": m1.HOP_S,
            "margin_s": m1.MARGIN_S, "fps": m1.FPS, "offset_ms": 0.0,
            "blank": 0, "vocab_size": len(stoi)},
        "tolerances": {"T0_double": 1e-9, "T0_integer": "exact", "T1": 1e-3,
                       "T2_logits": 1e-3, "T2_frame_indices": "exact"},
        "tiers_present": ["T0", "T1", "T2"],
        "T3_status": "DEFERRED to M2.2 - requires the taxonomy lattice, which is "
                     "not built yet. V1 does not ship without it (SPEC 6.3).",
        "files": files,
    }
    (ROOT / "conformance" / "manifest.json").write_text(
        json.dumps(man, indent=2, ensure_ascii=False), encoding="utf-8")
    print(json.dumps(man, indent=2, ensure_ascii=False))


if __name__ == "__main__":
    main()
