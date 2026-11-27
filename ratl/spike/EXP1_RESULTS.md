# RATL Rung 3 — Experiment 1 Results

**Date:** 2026-08-15
**Candidate:** `tarteel-ai/whisper-tiny-ar-quran` encoder + CTC head + explicit `|` separator
**Verdict (pre-registered criteria):** **H1_PARTIAL**

---

## 1. Gate results — all MEASURED

| Gate | Threshold | Failed model | **Experiment 1** | Result |
|---|---|---|---|---|
| **G1** size | ≤ 120 MB | 116.28 MB (3.1% margin) | **10.07 MB** | ✅ **PASS**, 91.6% headroom |
| **G2** boundary acc | ≥ 85% | 56.11% | **67.76%** | ❌ **FAIL** (+11.65 pts) |
| **G5** RTF (est.) | ≤ 0.5 | 0.99–1.66 IMPLAUSIBLE | **0.061–0.102** | ✅ **PLAUSIBLE**, 5–8× headroom |

Desktop CPU RTF, 1 thread, **including log-mel extraction in the timed loop**:
**0.0204** vs the failed model's 0.331 — **16× faster**. Params 7.93M, ONNX fp32
30.31 MB → int8 10.07 MB.

**The GPU eval RTF of 0.0014 is NOT a G5 figure** and is excluded from all
comparisons. Every RTF above is CPU-onnxruntime int8, identical basis to §07/§08.

---

## 2. G2 detail vs baseline — MEASURED

| Metric | Baseline | Experiment 1 | Δ |
|---|---|---|---|
| **G2 (all boundaries)** | 0.5611 | **0.6776** | **+0.1165** |
| Word **start** accuracy | 0.6820 | **0.7882** | +0.1062 |
| **Word end** accuracy | 0.4403 | **0.5670** | **+0.1267** |
| Median abs error | 124.1 ms | **83.3 ms** | −40.8 ms |
| p90 abs error | 1024.7 ms | **625.3 ms** | −399.4 ms |
| p99 abs error | 5986.6 ms | 5425.9 ms | −560.7 ms |

By recitation speed:

| Speed | Baseline | Experiment 1 | Δ |
|---|---|---|---|
| moderate (Alafasy) | 0.6769 | **0.7690** | +0.092 |
| medium (Minshawy Murattal) | 0.5957 | **0.7401** | +0.144 |
| fast (Hani Rifai) | 0.5764 | **0.7073** | +0.131 |
| measured (Husary) | 0.6105 | **0.6975** | +0.087 |
| **very_slow (Mujawwad)** | 0.3401 | **0.4684** | +0.128 |

`q` words 0.635 · `ḥ` words 0.7048 · **OOV 0** — text pipeline still clean.

---

## 3. What this establishes

1. **The word-separator hypothesis is directionally correct.** Word-end accuracy
   — the specific structural defect identified in `DAY3_NOTES.md` §3.1 — improved
   most of any metric (+12.67 pts). The `|` token addressed the diagnosed cause.
2. **The architecture switch solved the deployment problem outright.** G1 went
   from a 3.1% margin to 91.6% headroom; G5 went from IMPLAUSIBLE to PLAUSIBLE
   with 5–8× headroom. **RATL now has a model that fits the device.**
3. **Every speed class improved**, including the Mujawwad failure case.
4. **G2 still fails SPEC (67.76% vs 85%).** No threshold was moved.

---

## 4. The binding constraint is data, not architecture — MEASURED

Training log: train CTC **0.0010** by epoch 30 while val CTC bottomed at
**0.4006 at epoch 6** and then rose. That is unambiguous overfitting.

**Experiment 1 used 6.0 h of the 185.01 h clean pool — 3.2%.**

The heavy tail persists (p99 5.4 s, max 11.1 s), which is consistent with a model
that has memorised 6 h rather than learned the task.

---

## 5. Deviation from the pre-registered next step — declared

`RUNG3_PLAN.md` §5 pre-registered: **H1_PARTIAL → run Experiment 2 (auxiliary
boundary head)**.

The overfitting evidence in §4 was not anticipated when that rule was written.
Adding model capacity/objectives to a model that is already overfitting 3.2% of
the available data is the wrong order of operations.

**Recommended order, stated explicitly as a deviation rather than applied
silently:**

1. **Experiment 1b — scale data** to ~30 h across ~9 reciters (all speed classes,
   extra Mujawwad). Cheapest, highest expected value, no architecture change.
2. **Experiment 2 — boundary head**, run after 1b, so its contribution is
   measured against a data-saturated baseline rather than confounded with it.

Experiment 2 remains committed, not cancelled. Only its ordering changed, and
the reason is recorded here.

---

## 6. Conditions held constant

- `05_g2_alignment.py` **not modified**; `13_eval_exp1.py` imports its alignment
  algorithm, ground-truth loader, corpus, tolerance and metrics directly.
- RATL-Bench-v0 unchanged; all 30 bench ayat held out of training across all reciters.
- SPEC thresholds unchanged.
- $0 — local RTX 4060, 291 s training.
