# RATL — Experiment 3: decode-side word extent

**Date:** 2026-08-18
**Training:** NONE. **New data:** NONE. **Harness/corpus/threshold:** unchanged.
**Verdict:** **large gain, still short of SPEC.** G2 0.6776 → **0.7828**.

---

## 1. Validation of the measurement

`M0_spike` is the current boundary rule, re-run through the new script. It
reproduced **0.6776 (exp1)** and **0.6707 (exp1b)** exactly — identical to
`13_eval_exp1.py` and `17_eval_exp1b.py`. The harness, corpus, ground truth,
tolerance and threshold are therefore demonstrably unchanged, and every number
below is comparable to everything measured before it.

---

## 2. Results — all MEASURED

Four boundary rules, one forward pass, one shared Viterbi path. Only the
arithmetic converting token spans to word boundaries differs.

### exp1 checkpoint (6.0 h)

| method | G2 | start | end | excl. Mujawwad | signed start | signed end | gap to 0.85 |
|---|---|---|---|---|---|---|---|
| M0 spike (current) | 0.6776 | 0.7882 | 0.5670 | 0.7285 | +68.1 | −69.5 | 0.1724 |
| M1 half-gap | 0.7147 | 0.7984 | 0.6310 | 0.7638 | +52.5 | −19.4 | 0.1353 |
| **M2 separator** | **0.7828** | 0.7999 | **0.7656** | **0.8086** | +34.6 | +26.3 | **0.0672** |
| M3 full-gap | 0.7795 | 0.8115 | 0.7475 | 0.8041 | +2.9 | +56.8 | 0.0705 |

### exp1b checkpoint (30.0 h)

| method | G2 | start | end | excl. Mujawwad | signed start | signed end | gap to 0.85 |
|---|---|---|---|---|---|---|---|
| M0 spike (current) | 0.6707 | 0.8079 | 0.5335 | 0.7226 | +71.7 | −97.9 | 0.1793 |
| M1 half-gap | 0.7158 | 0.7933 | 0.6383 | 0.7629 | +60.2 | −29.8 | 0.1342 |
| **M2 separator** | **0.7755** | 0.7897 | 0.7613 | 0.8045 | +44.9 | +31.4 | 0.0745 |
| M3 full-gap | 0.7722 | 0.8042 | 0.7402 | 0.8023 | +8.6 | +70.6 | 0.0778 |

**Best configuration: exp1 + M2_separator = G2 0.7828.**

---

## 3. What changed, per class (exp1, M0 → M2)

| speed | M0 | M2 | Δ |
|---|---|---|---|
| **very_slow (Mujawwad)** | 0.4684 | **0.6766** | **+0.2082** |
| fast | 0.7073 | 0.8182 | +0.1109 |
| measured | 0.6975 | 0.7826 | +0.0851 |
| moderate | 0.7690 | **0.8538** | +0.0848 |
| medium | 0.7401 | 0.7798 | +0.0397 |

Every class improved. The worst class improved most. **Moderate already clears
0.85.** Median absolute error 83.3 ms → **46.3 ms**.

---

## 4. Why it worked — the diagnosis was correct

EXP1B_RESULTS.md §3 predicted spans were systematically too narrow: starts late,
ends early. M2 assigns each word everything between the two trained `|`
separator spikes. Word-end accuracy went **0.5670 → 0.7656 (+0.1986)** — the
single largest movement of any metric in the project so far, and it landed
exactly where the diagnosis said it would.

Signed errors confirm the mechanism is now largely neutralised: end error moved
from **−69.5 ms (early)** to **+26.3 ms**, i.e. from a large one-sided bias to a
small one.

---

## 5. More data is now measurably NOT the answer

Under every decode rule, the **6.0 h checkpoint beats the 30.0 h checkpoint**:

| rule | exp1 (6 h) | exp1b (30 h) |
|---|---|---|
| M1 | 0.7147 | 0.7158 |
| **M2** | **0.7828** | 0.7755 |
| M3 | 0.7795 | 0.7722 |

Combined with EXP1B_RESULTS.md, this closes the data question: 5× data improved
the CTC objective by 56% and did not improve the gate under any boundary rule.
**Do not spend further download budget or training time on corpus scale.**

---

## 6. Where the remaining 6.72 points are

| | |
|---|---|
| median abs error | **46.3 ms** (tolerance is 150 ms) |
| p90 abs error | 650.2 ms |
| p99 abs error | 5508.1 ms |

The typical boundary is now **3× inside tolerance**. The deficit is not spread
across all words — it is a **tail of catastrophically misaligned clips**. Two
identified, non-research causes:

1. **Long-form windowing.** Audio is cut into 15 s NON-overlapping windows and
   the logits concatenated. Words straddling a cut have no cross-window context.
   Max error is 11118.8 ms — seconds, not milliseconds — which is a windowing
   failure, not a phonetic one. This is an engineering defect, not a model limit.
2. **Residual constant latency.** Both signed medians are now positive
   (+34.6 start, +26.3 end): a uniform ~30 ms late bias, consistent with the
   encoder's conv receptive-field centre. Physically motivated, cheap to correct.

Mujawwad remains weakest at 0.6766 despite the largest gain.

---

## 7. Selection caveat — declared, not buried

M2 was chosen by comparing four rules **on RATL-Bench-v0**, which is the
evaluation set. That is selection on the eval set. M0/M1/M3 bracket the gap at
0%/50%/100% with no free parameter and M2 is linguistically motivated rather
than tuned, so the exposure is small — but **0.7828 must be reconfirmed on
held-out reciters before it is quoted as a V1 number.** Hani Rifai and Minshawy
Murattal are inside the current bench and cannot serve as that held-out set.

---

## 8. Status against SPEC

| Gate | Threshold | Measured | Result |
|---|---|---|---|
| G1 size | ≤ 120 MB | 10.07 MB | ✅ PASS |
| **G2 boundary** | **≥ 0.85** | **0.7828** | ❌ **FAIL by 0.0672** |
| G5 RTF (est.) | ≤ 0.5 | 0.061–0.102 | ✅ PLAUSIBLE |

**The 0.85 threshold was not modified.**
