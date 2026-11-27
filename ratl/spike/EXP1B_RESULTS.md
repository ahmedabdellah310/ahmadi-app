# RATL Rung 3 — Experiment 1b Results

**Date:** 2026-08-18
**Change vs Experiment 1:** training data only — 6.0 h / 3 reciters → **30.0 h / 9 reciters**
**Verdict:** **G2 FAIL. Data-scaling hypothesis FALSIFIED for G2.**

---

## 1. Gate results — all MEASURED

| Gate | Threshold | Exp 1 (6.0 h) | **Exp 1b (30.0 h)** | Result |
|---|---|---|---|---|
| **G2** boundary acc | ≥ 0.85 | 0.6776 | **0.6707** | ❌ **FAIL**, −0.0069 |
| Best val CTC | (not a gate) | 0.4006 | **0.1760** | −56% |

**5× the data produced a 56% better training objective and a slightly WORSE gate.**
No threshold was moved. Harness, corpus, tolerance and metrics unchanged.

---

## 2. G2 detail — MEASURED

| Metric | wav2vec2 | Exp 1 | **Exp 1b** | Δ vs Exp 1 |
|---|---|---|---|---|
| **G2 (all boundaries)** | 0.5611 | 0.6776 | **0.6707** | **−0.0069** |
| Word **start** accuracy | 0.6820 | 0.7882 | **0.8079** | +0.0197 |
| **Word end** accuracy | 0.4403 | 0.5670 | **0.5335** | **−0.0335** |
| Median abs error | 124.1 ms | 83.3 ms | 91.0 ms | +7.7 ms |
| p90 abs error | 1024.7 ms | 625.3 ms | 601.8 ms | −23.5 ms |
| p99 abs error | 5986.6 ms | 5425.9 ms | 4713.3 ms | −712.6 ms |
| max abs error | — | 11118.8 ms | 11118.8 ms | 0.0 |

Starts improved, **ends regressed by more**, so the gate went backwards.

---

## 3. Root cause — CTC peak sharpening — MEASURED

Signed error medians (positive = predicted late):

| | start | % late | end | % late |
|---|---|---|---|---|
| Exp 1 | +68.2 ms | 87.8% | −69.5 ms | 31.8% |
| **Exp 1b** | **+71.7 ms** | **88.9%** | **−97.9 ms** | **22.6%** |

The pattern is unambiguous and it is **structural to CTC**:

- **Starts are predicted LATE** (~89% of the time)
- **Ends are predicted EARLY** (~77% of the time)
- Therefore **every word span is systematically too narrow**

CTC is a *transcription* objective. It places each character on a sharp
probability spike surrounded by blanks; it is never asked where the acoustic
extent of a word begins or ends. Training it better makes the spikes **peakier**,
which **shrinks the predicted span further**.

That is why Exp 1b is worse: start +68.2 → +71.7 ms and end −69.5 → −97.9 ms.
**Lower CTC loss actively degrades boundary extent past a point.**
G2 and CTC loss are anti-correlated in this regime. More data cannot fix it.

---

## 4. Mujawwad is the extreme case of the same defect — MEASURED

| | value |
|---|---|
| Mujawwad G2 | **0.4572** (Exp 1: 0.4684) |
| Mujawwad end error median | **−296.6 ms** (early) |
| Mujawwad end \|err\| p50 / p90 | 380 ms / 1671 ms |
| **G2 excluding Mujawwad** | **0.7226** |

Mujawwad elongates vowels (madd) for hundreds of ms. CTC spikes at the character
onset and emits blank across the entire elongation, so the predicted end lands
near the *start* of a long vowel. It is not a separate problem — it is peak
sharpening amplified by long phones.

Mujawwad training data is **already at its ceiling** (both Mujawwad reciters were
capped by the ≤15 s clip filter), so more Mujawwad data is not available without
changing the 15 s window.

---

## 5. Speaker generalisation is NOT the problem — MEASURED

| Group | n | acc |
|---|---|---|
| Seen voices (3 trained) | 822 | 0.6405 |
| **Unseen voices (2 never trained)** | 552 | **0.7156** |

Unseen scored *higher*. This is **confounded**: the seen group contains Mujawwad
(0.4572). Comparing like with like:

| Reciter | trained? | acc |
|---|---|---|
| Alafasy | yes | 0.7635 |
| Husary | yes | 0.6957 |
| **Minshawy Murattal** | **no** | **0.7347** |
| **Hani Rifai** | **no** | **0.6964** |
| Abdul Basit Mujawwad | yes | 0.4572 |

Unseen voices sit squarely inside the seen range. **RATL generalises to new
speakers already.** The failure is the boundary objective, not speaker adaptation.

---

## 6. What Experiment 1b did establish

1. **Data was the binding constraint for the CTC objective** — val 0.4006 → 0.1760,
   and Exp 1's memorisation (train 0.0010 vs val 0.4006) is gone (0.0184 vs 0.1760).
2. **Data is NOT the binding constraint for G2.** Falsified, cleanly.
3. **Speaker generalisation is solved** at the accuracy level the model can reach.
4. **Word starts are usable** (0.8079); word ends are the whole deficit.
5. Text pipeline still **0 OOV**; corpus 99.992% clean (1 corrupt mp3 of 13,188).
6. The gate deficit is now attributable to **one identified mechanism**, not a mystery.

---

## 7. Correction to the record

After training I stated "data was the binding constraint — measured, not argued."
That was correct **for val CTC** and **wrong for G2**, which is the gate that
matters. Experiment 1b improved the objective and did not improve the gate. The
caution I attached at the time (CTC gains need not transfer to boundaries) is
what actually held.

---

## 8. Implication for the next step

Experiment 2 (auxiliary frame-level word-boundary head) was pre-registered in
`RUNG3_PLAN.md` §5 and deferred in `EXP1_RESULTS.md` §5 pending data scaling.
Data scaling is now done and falsified. **Experiment 2 is the indicated next
step, and the diagnosis in §3 sharpens its design**: the head must supervise
word *extent*, especially word *ends*, not merely token identity.

A second, cheaper candidate is a **decode-side fix**: derive boundaries from the
blank posterior's acoustic extent rather than from the collapsed token spike.
This changes no training at all. It does change the alignment algorithm, so it
must be reported alongside the current method, not silently substituted — the
frozen corpus, ground truth, tolerance and 0.85 threshold stay fixed either way.

Neither requires new data. **No further download is needed.**
