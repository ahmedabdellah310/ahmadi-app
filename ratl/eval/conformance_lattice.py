"""Conformance: with all error arcs disabled the lattice IS the frozen aligner.

This is the test that lets the error lattice exist at all. If it passes, adding
skip/garbage/repeat arcs cannot have changed G2 = 0.8570, because with the arcs
off the Viterbi is bit-identical to g2.ctc_forced_align - not close, identical.

Three independent checks:

  1. SYNTHETIC   the T0 forced-alignment golden vectors
  2. REAL        the T2 decode vectors (real clips, real model, real target)
  3. BENCH       every clip of RATL-Bench-v0, comparing both the label spans and
                 the M2E_edge_spike word boundaries the G2 metric is computed from

Failure is fatal and reported per case. Nothing here tunes anything.
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
v2 = _load("v2", "verify_onnx_g2_e2e.py", ROOT / "models")
lat = _load("lat", "lattice.py", ROOT / "eval")

OUT = ROOT / "models" / "out"
VEC = ROOT / "conformance" / "vectors"
REPORT = ROOT / "eval" / "conformance_lattice.json"
MODEL = "ratl-align-v1-e2e-fp32.onnx"
OFF = lat.LatticeConfig()          # all arcs -inf: the frozen configuration


def spans_via_lattice(logp, ids, word_spans):
    path, _, tb = lat.viterbi(logp, ids, word_spans, OFF)
    return lat.frozen_spans(path, ids, tb["n_ctc"])


def check_t0():
    """Synthetic logits from the T0 golden vectors."""
    v = json.loads((VEC / "t0_forced_align.json").read_text(encoding="utf-8"))
    fails, n = [], 0
    for c in v["cases"]:
        logits = np.asarray(c["logits"], dtype=np.float64)
        labels = c["labels"]
        logp = g2.log_softmax(logits)
        want = [tuple(x) if x else None for x in c["spans"]]
        ref = g2.ctc_forced_align(logp, labels, blank=0)
        spans = [(0, len(labels))]           # treat the whole thing as one word
        got = spans_via_lattice(logp, labels, spans)
        n += 1
        if got != ref or got != want:
            fails.append({"case": c["note"], "want": want, "frozen": ref, "lattice": got})
    return {"n_cases": n, "n_fail": len(fails), "failures": fails[:5]}


def check_t2():
    """Real clips from the T2 golden vectors, re-run through the shipping model."""
    import onnxruntime as ort
    v = json.loads((VEC / "t2_decode.json").read_text(encoding="utf-8"))
    itos = json.loads((OUT / "vocab.json").read_text(encoding="utf-8"))
    stoi = {c: i for i, c in enumerate(itos)}
    so = ort.SessionOptions()
    so.intra_op_num_threads = 1
    sess = ort.InferenceSession(str(OUT / MODEL), so, providers=["CPUExecutionProvider"])
    fails, n = [], 0
    for c in v["cases"]:
        pcm = np.frombuffer((VEC / c["pcm_file"]).read_bytes(), dtype=np.int16)
        audio = (pcm.astype(np.float32) / 32768.0)
        logits = v2.run_windows(audio, sess)
        vf = max(1, int(min(len(pcm) / m1.SR * m1.FPS, tr.ENC_FRAMES)))
        logits = logits[:vf]
        logp = g2.log_softmax(logits.astype(np.float64))
        words = [{"tr": w} for w in c["words"]]
        ids, wspans, _ = e1.build_tokens_v2(words, stoi)
        ref = g2.ctc_forced_align(logp, ids, blank=0)
        got = spans_via_lattice(logp, ids, wspans)
        want = [tuple(x) if x else None for x in c["token_spans"]]
        n += 1
        if got != ref or got != want:
            bad = [i for i, (a, b) in enumerate(zip(got, ref)) if a != b][:5]
            fails.append({"clip": c["pcm_file"], "n_mismatch_vs_frozen":
                          sum(1 for a, b in zip(got, ref) if a != b),
                          "n_mismatch_vs_vector":
                          sum(1 for a, b in zip(got, want) if a != b),
                          "first_bad_labels": bad})
    return {"n_cases": n, "n_fail": len(fails), "failures": fails[:5]}


def check_bench():
    """Whole of RATL-Bench-v0: label spans AND the M2E word boundaries G2 uses."""
    import librosa
    import onnxruntime as ort
    itos = json.loads((OUT / "vocab.json").read_text(encoding="utf-8"))
    stoi = {c: i for i, c in enumerate(itos)}
    so = ort.SessionOptions()
    so.intra_op_num_threads = 1
    sess = ort.InferenceSession(str(OUT / MODEL), so, providers=["CPUExecutionProvider"])
    gt = g2.load_gt()
    translit = {k: g2.fetch_translit(*k) for k in g2.AYAT}

    n_clips = n_lab = n_bound = 0
    bad_lab = bad_bound = 0
    fails = []
    t0 = time.time()
    for rec, _ in g2.RECITERS.items():
        for s, a in g2.AYAT:
            if not gt[rec].get((s, a)):
                continue
            p = SPIKE / ".cache" / "audio" / rec / f"{s:03d}{a:03d}.mp3"
            if not p.exists():
                continue
            audio, _ = librosa.load(str(p), sr=g2.SR, mono=True)
            words = translit[(s, a)]
            ids, wspans, _ = e1.build_tokens_v2(words, stoi)
            if not ids:
                continue
            logp = g2.log_softmax(v2.run_windows(audio, sess).astype(np.float64))
            T = logp.shape[0]
            ref = g2.ctc_forced_align(logp, ids, blank=0)
            got = spans_via_lattice(logp, ids, wspans)
            n_clips += 1
            n_lab += len(ids)
            d = sum(1 for x, y in zip(got, ref) if x != y)
            bad_lab += d
            for wi in range(len(wspans)):
                b_ref = d3.word_bounds("M2E_edge_spike", wspans, ref, wi, len(ids), T)
                b_got = d3.word_bounds("M2E_edge_spike", wspans, got, wi, len(ids), T)
                n_bound += 1
                if b_ref != b_got:
                    bad_bound += 1
            if d and len(fails) < 5:
                fails.append({"clip": f"{rec} {s}:{a}", "n_label_mismatch": d})
        print(f"      {rec:<34} ({time.time()-t0:.0f}s)", flush=True)
    return {"n_clips": n_clips, "n_labels": n_lab, "n_label_mismatch": bad_lab,
            "n_word_boundaries": n_bound, "n_boundary_mismatch": bad_bound,
            "failures": fails}


def main():
    rep = {"step": "M2.0 conformance - lattice reduces to the frozen aligner",
           "config": "lam_skip = lam_ins = lam_rep = -inf",
           "frozen_reference": "g2.ctc_forced_align (05_g2_alignment.py, unmodified)"}
    rep["T0_synthetic"] = check_t0()
    rep["T2_real_clips"] = check_t2()
    rep["bench_full"] = check_bench()

    ok = (rep["T0_synthetic"]["n_fail"] == 0
          and rep["T2_real_clips"]["n_fail"] == 0
          and rep["bench_full"]["n_label_mismatch"] == 0
          and rep["bench_full"]["n_boundary_mismatch"] == 0)
    rep["INVARIANT_HOLDS"] = bool(ok)
    rep["meaning"] = ("With the error arcs disabled the lattice is bit-identical to "
                      "the frozen aligner on every checked case, so G2 = 0.8570 is "
                      "preserved by construction." if ok else
                      "INVARIANT VIOLATED - the lattice must not be used until fixed.")
    REPORT.write_text(json.dumps(rep, indent=2, ensure_ascii=False), encoding="utf-8")

    print("\n" + "=" * 70)
    print("LATTICE CONFORMANCE - arcs disabled must equal the frozen aligner")
    print("=" * 70)
    print(f"  T0 synthetic   {rep['T0_synthetic']['n_cases']} cases, "
          f"{rep['T0_synthetic']['n_fail']} fail")
    print(f"  T2 real clips  {rep['T2_real_clips']['n_cases']} cases, "
          f"{rep['T2_real_clips']['n_fail']} fail")
    b = rep["bench_full"]
    print(f"  bench          {b['n_clips']} clips, {b['n_labels']} labels, "
          f"{b['n_label_mismatch']} label mismatches")
    print(f"                 {b['n_word_boundaries']} M2E boundaries, "
          f"{b['n_boundary_mismatch']} mismatches")
    print(f"\n  INVARIANT HOLDS: {rep['INVARIANT_HOLDS']}")
    if not ok:
        raise SystemExit(1)


if __name__ == "__main__":
    main()
