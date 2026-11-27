"""T3 conformance vectors: full Assessment output from the reference lattice.

SPEC 6.3 makes conformance vectors blocking for V1. T0-T2 pin the alignment
path; T3 pins the TAXONOMY, which is what M2.2 adds. With the Rust core deferred
(SPEC 6.1) these vectors are the executable specification any future
implementation must satisfy.

Generated from eval/lattice.py - the reference whose held-out gate produced
G3 = 0.9333 and G4 = 0.0085 - under the gated V1 configuration. Cases are drawn
from the seeded mutation corpus so every error class is represented, plus clean
and null-mutation controls.

Statuses and word indices are asserted EXACTLY. Confidence is continuous and
carries a tolerance.
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
sys.path.insert(0, str(SPIKE))


def _load(name, fn, base=SPIKE):
    sp = importlib.util.spec_from_file_location(name, base / fn)
    m = importlib.util.module_from_spec(sp)
    sys.modules[name] = m
    sp.loader.exec_module(m)
    return m


g2 = _load("g2", "05_g2_alignment.py")
v2 = _load("v2", "verify_onnx_g2_e2e.py", ROOT / "models")
lat = _load("lat", "lattice.py", ROOT / "eval")
mut = _load("mut", "corpus/mutate.py", ROOT / "eval")

OUT = ROOT / "models" / "out"
MODEL = "ratl-align-v1-e2e-fp32.onnx"
SEED = 1470
PER_OP = 2

# The gated V1 configuration. tau_sub_z is +inf: V1 does not assert
# SUBSTITUTION (SPEC 4.1); candidates fall through to the abstain band.
V1 = dict(lam_skip=-20.0, lam_ins=-20.0, lam_rep=-25.0, lam_trunc=-40.0,
          garb_pen=2.0, tau_sub_z=float("inf"), tau_sub_z_abstain=6.0,
          tau_omit_abstain=-8.0, tau_uncertain=float("-inf"))


def main():
    import onnxruntime as ort

    itos = json.loads((OUT / "vocab.json").read_text(encoding="utf-8"))
    stoi = {c: i for i, c in enumerate(itos)}
    so = ort.SessionOptions()
    so.intra_op_num_threads = 1
    sess = ort.InferenceSession(str(OUT / MODEL), so, providers=["CPUExecutionProvider"])
    cfg = lat.LatticeConfig(**V1)

    items = []
    for tier, recs, bench_only in (("seen", mut.SEEN_RECITERS, False),
                                   ("disjoint", mut.UNSEEN_RECITERS, True)):
        items += mut.build_tier(tier, recs, bench_only, PER_OP, SEED)
    items += [dict(x, op="clean", expected_status="CORRECT")
              for x in mut.build_tier("seen", mut.SEEN_RECITERS, False, 3, SEED + 1)]

    cases = []
    for it in items:
        try:
            audio, _segs, _wi = mut.render(it)
        except Exception:  # noqa: BLE001
            continue
        if audio is None or len(audio) < mut.SR // 2:
            continue
        words = mut.translit_offline(it["surah"], it["ayah"])
        if not words:
            continue
        tr = [(w["tr"] or "").strip().lower() for w in words]
        logp = g2.log_softmax(v2.run_windows(audio, sess).astype(np.float64))
        llrs = lat.llr_all(logp, words, stoi)
        a = lat.assess(logp, words, stoi, cfg, model_id="t3", llr_cache=llrs)

        pcm = np.clip(np.round(audio * 32768.0), -32768, 32767).astype(np.int16)
        name = f"t3_pcm_{it['id']}.s16"
        (VEC / name).write_bytes(pcm.tobytes())

        cases.append({
            "id": it["id"], "op": it["op"], "tier": it["tier"],
            "expected_status": it["expected_status"],
            "mutated_word_index": it["word_index"],
            "pcm_file": name, "n_samples": int(pcm.size),
            "words": tr,
            "audio_duration_ms": a.audio_duration_ms,
            "llr_per_token": {str(k): round(v["per_token"], 6) for k, v in llrs.items()},
            "results": [{"index": w.index, "status": w.status,
                         "confidence": round(w.confidence, 4),
                         "t_start_ms": w.t_start_ms, "t_end_ms": w.t_end_ms}
                        for w in a.words],
            "events": a.events,
        })

    payload = {
        "tier": "T3",
        "fn": "Engine.assess - full Assessment",
        "model": MODEL,
        "spec_version": "0.4",
        "config": {k: (None if v in (float("inf"), float("-inf")) else v)
                   for k, v in V1.items()},
        "config_note": "tau_sub_z is +inf: V1 does not assert SUBSTITUTION (SPEC 4.1)",
        "tolerances": {"status": "exact", "index": "exact",
                       "t_start_ms": "exact", "t_end_ms": "exact",
                       "confidence": 1e-3, "llr_per_token": 1e-3},
        "pcm_format": "int16 LE mono 16 kHz; divide by 32768.0 for float",
        "n_cases": len(cases),
        "cases": cases,
    }
    p = VEC / "t3_assessment.json"
    p.write_text(json.dumps(payload, indent=1, ensure_ascii=False), encoding="utf-8")

    man_path = ROOT / "conformance" / "manifest.json"
    man = json.loads(man_path.read_text(encoding="utf-8"))
    man["spec_version"] = "0.4"
    man["tiers_present"] = ["T0", "T1", "T2", "T3"]
    man.pop("T3_status", None)
    man["tolerances"]["T3_status"] = "exact"
    man["tolerances"]["T3_confidence"] = 1e-3
    man["files"] = [f for f in man["files"] if f["file"] != "t3_assessment.json"]
    man["files"].append({"file": "t3_assessment.json",
                         "sha256": hashlib.sha256(p.read_bytes()).hexdigest(),
                         "bytes": p.stat().st_size})
    man_path.write_text(json.dumps(man, indent=2, ensure_ascii=False), encoding="utf-8")

    from collections import Counter
    dist = Counter(w["status"] for c in cases for w in c["results"])
    print(f"T3: {len(cases)} cases -> {p.name}")
    print("  status distribution:", dict(dist))
    print("  ops:", dict(Counter(c["op"] for c in cases)))


if __name__ == "__main__":
    main()
