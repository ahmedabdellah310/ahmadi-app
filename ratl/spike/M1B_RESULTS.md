# RATL M1b — Mujawwad tail repair

**Date:** 2026-08-18 · **Days to Oct 10:** 53
**Training:** none · **Downloads:** none · **Bench/threshold:** unchanged
**Result: both approaches FALSIFIED on held-out data. G2 remains 0.8395 — FAIL by 0.0105.**

---

## 1. Held-out selection — RATL-Val-v0, bench untouched

| rule | top_db | G2 | start | end | Mujawwad | other |
|---|---|---|---|---|---|---|
| **M2E (incumbent)** | — | **0.8787** | 0.9094 | 0.8479 | **0.7564** | 0.8960 |
| M2S speech-edge | 25 | 0.8671 | 0.9106 | 0.8236 | 0.7548 | 0.8830 |
| M2S speech-edge | 30 | 0.8573 | 0.9090 | 0.8056 | 0.7379 | 0.8742 |
| M2S speech-edge | 35 | 0.8476 | 0.9052 | 0.7901 | 0.7307 | 0.8642 |
| M2S speech-edge | 40 | 0.8429 | 0.8998 | 0.7861 | 0.7275 | 0.8593 |

**Approach A (speech-extent edges) is worse at every threshold**, and worse on
Mujawwad specifically — the class it was designed to fix.

Why it failed: in continuous recitation there is no internal silence, so the
speech run spans the whole ayah. Extending the outer edge to the speech-run
boundary reintroduces exactly the silence-swallowing defect that M2E fixed, and
the harm to multi-word ayat exceeds the gain on 1–2 word ayat.

**The selected approach is therefore the incumbent, M2E, unchanged.** The bench
figure stands at **0.8395**; re-running a deterministic, unchanged pipeline on
the frozen bench would reproduce it exactly, so no redundant gate run was spent.

---

## 2. Approach B — confidence → UNCERTAIN

Per-word mean Viterbi path log-probability, on held-out data:

| abstain | retained G2 | retained Mujawwad |
|---|---|---|
| 0% | 0.8787 | 0.7564 |
| 2% | 0.8802 | 0.7593 |
| 5% | 0.8809 | 0.7625 |
| 10% | 0.8830 | 0.7632 |

**The confidence signal is weak.** Discarding the least-confident 10% of words
recovers only +0.0043. Path likelihood does not identify the catastrophic
boundaries — the model is confident about the characters it emits and simply
places them in the wrong place in time.

**G2 was computed over ALL words throughout.** Abstention is reported as a
product capability (SPEC §4), never applied to the gate. Using it to lift the
gate would be reinterpreting the 0.85 requirement.

---

## 3. The real failure mode — MEASURED, and it is not "Mujawwad"

Accuracy against ground-truth word duration, **all reciters**:

| GT word duration | n | acc | Mujawwad subset |
|---|---|---|---|
| 0–500 ms | 109 | **0.9495** | 0.9375 |
| 500–1000 ms | 427 | 0.8923 | 0.7714 |
| 1000–2000 ms | 556 | 0.8399 | 0.7740 |
| 2000–4000 ms | 229 | 0.7686 | 0.6979 |
| **4000+ ms** | 53 | **0.4906** | 0.3269 |

Accuracy degrades **monotonically with word duration, for every reciter**.

GT word duration: Mujawwad p50 **1780 ms** / p90 **3846 ms**; others p50 1060 ms
/ p90 2370 ms.

**Mujawwad is not a distinct failure class. It is the class with the longest
words.** The tolerance is a fixed ±150 ms regardless of word length, so a 4 s
madd must be located to 3.75% relative precision while a 500 ms word needs only
30%. CTC spike placement has roughly proportional error, so long words fail.

This also explains why every decode-side rule plateaus here: no rearrangement of
spike/blank/separator geometry adds temporal information that the CTC objective
never learned.

---

## 4. Decode-side work is now exhausted

Six boundary rules tested, all on identical logits and Viterbi paths:
M0 spike, M1 half-gap, M2 separator, M2E edge-aware, M2S speech-edge, M3 full-gap.
Plus overlap windowing (+0.0031) and a latency offset sweep (0 ms won).

Best: **M2E + overlap + 0 ms = bench G2 0.8395**, held-out 0.8787.

---

## 5. Smallest justified next step — Experiment 2, the pre-registered boundary head

Pre-registered in `RUNG3_PLAN.md` §5, deferred in `EXP1_RESULTS.md` §5 for data
scaling, deferred again for decode-side repair. Both deferrals are now closed by
measurement: data does not help (Exp 1b), decode does not close it (M1/M1b).

Add a frame-level word-boundary head trained alongside the CTC head, so boundary
placement is **supervised directly** rather than inferred from transcription
spikes. This is the only remaining lever that adds temporal supervision.

- **No new data** — the 30 h corpus is on disk and has word-level ground truth.
- **No new downloads.**
- **Requires training** — ~11 min GPU per run (measured), ~3 h implementation.
- Falsifiable: if the boundary head does not improve the 2000 ms+ duration
  buckets, the architecture cannot reach 0.85 and V1 must be re-scoped.

**The 0.85 threshold was not modified or reinterpreted at any point.**
