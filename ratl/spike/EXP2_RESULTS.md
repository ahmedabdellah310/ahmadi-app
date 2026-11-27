# RATL Experiment 2 — auxiliary boundary head — **G2 GATE PASSED**

**Date:** 2026-08-18 · **Days to Oct 10:** 53
**Downloads:** none · **New data:** none · **Bench/threshold:** unchanged
**Result: G2 0.8570 ≥ 0.85 — PASS by 0.0070**

---

## 1. Gate result — RATL-Bench-v0, harness unmodified

| | |
|---|---|
| **G2** | **0.8570** ✅ **PASS** (threshold 0.85) |
| Word start | 0.8872 |
| Word end | 0.8268 |
| Excluding Mujawwad | 0.8896 |
| median / p90 abs error | 40.0 ms / 310.0 ms |

By recitation speed:

| class | G2 | |
|---|---|---|
| moderate | 0.9152 | ✅ |
| medium | 0.8899 | ✅ |
| fast | 0.8782 | ✅ |
| measured | 0.8750 | ✅ |
| very_slow (Mujawwad) | 0.7230 | below, no longer blocking |

By ground-truth word duration:

| duration | G2 |
|---|---|
| < 1 s | 0.9198 |
| 1–2 s | 0.8579 |
| 2–4 s | 0.7904 |
| > 4 s | 0.5000 |

---

## 2. Full progression

| stage | bench G2 | note |
|---|---|---|
| wav2vec2 | 0.5611 | rejected on G1/G5 |
| Exp 1 — whisper-tiny + CTC, 6 h | 0.6776 | |
| Exp 1b — 30 h | 0.6707 | data falsified |
| Exp 3 — M2 separator decode | 0.7828 | |
| M1 — + edge-aware rule + overlap | 0.8395 | decode exhausted |
| **Exp 2 — + boundary head (multi-task)** | **0.8570** | **PASS** |

**+0.1794 over Experiment 1**, of which **+0.1619 came from decode-side fixes
requiring no training at all**, and the final **+0.0175** from the auxiliary head.

---

## 3. The head helped as a TRAINING signal, not as a decoder

This is the surprising part, and it inverts the pre-registered expectation.

Held-out comparison of decode variants (RATL-Val-v0):

| variant | G2 | Mujawwad | 2–4 s | > 4 s |
|---|---|---|---|---|
| **V_m2e** (CTC decode, exp2 model) | **0.8941** | 0.7934 | 0.7813 | 0.8913 |
| V_win_25 (boundary DP, ±0.5 s) | 0.8801 | **0.8264** | **0.8105** | 0.8478 |
| V_win_50 | 0.7709 | 0.7910 | 0.8027 | 0.7826 |
| V_win_100 | 0.5913 | 0.6222 | 0.7281 | 0.7174 |
| V_mean | 0.5004 | 0.4405 | 0.4683 | 0.4348 |
| V_dp (pure boundary DP) | 0.4844 | 0.4445 | 0.4571 | 0.4130 |

**Every decode variant that consumes the boundary posteriors lost.** The pure DP
scored 0.4844 — near chance. The head's posteriors are too diffuse to place
boundaries directly.

What did improve is the CTC alignment itself: the *same* M2E decoder scored
0.8787 on the exp1 checkpoint and **0.8941** on the exp2 checkpoint. Training
against frame-level boundary targets sharpened the shared encoder's temporal
representation, and the CTC head inherited it.

**So the boundary head ships as an auxiliary loss and is not used at inference.**
Its 2-channel output adds ~1.5 k parameters and can be dropped from the exported
model entirely.

One caveat worth keeping: V_win_25 beat the winner on Mujawwad (0.8264 vs
0.7934) and on 2–4 s words. A duration-conditional hybrid is a plausible future
gain, deliberately NOT taken here — it would add a selection degree of freedom
for ~0.01, and the gate is already passed.

---

## 4. Selection discipline held

Config chosen on RATL-Val-v0 (552 held-out items, 6 reciters the exp1 checkpoint
never trained on). Bench run **once**.

| | held-out | bench | drop |
|---|---|---|---|
| M1 | 0.8787 | 0.8395 | 0.0392 |
| **Exp 2** | 0.8941 | 0.8570 | 0.0371 |

The val→bench drop is **stable across two independent selections** (0.039 vs
0.037). That consistency is evidence the gain is real rather than selection
noise on the validation set.

---

## 5. Honest statement of the margin

The margin is **0.0070 — seven tenths of a point**. This is a pass, not a
comfortable one. Two things to keep in view:

1. Words longer than 4 s still score **0.50**, and Mujawwad remains at 0.723.
   The mechanism identified in M1B_RESULTS.md §3 (fixed ±150 ms tolerance is
   proportionally harsher on long words) is mitigated, not eliminated.
2. RATL-Bench-v0 is 150 clips / 1374 words. A margin this thin should be
   re-confirmed on a larger bench before it is quoted as a headline claim.

**The 0.85 threshold was never modified, lowered, or reinterpreted, and G2 was
computed over all words at every stage.**

---

## 6. Frozen configuration

```
model    whisper-tiny-ar-quran encoder + CTC head + auxiliary boundary head
         trained multi-task on 30.0 h / 9 reciters, best epoch 7
inference CTC head only; boundary head is training-time only
windowing 15 s window, 10 s hop, 2.5 s context margins discarded
frames    50 fps, t = frame * 20 ms
boundary  M2E_edge_spike -- '|' separator where one exists, CTC spike at ayah edges
offset    0 ms
```

**Alignment and decoder are FROZEN. Next: M2 — RATL Core, SPEC §5 API, `ratl-eval`.**
