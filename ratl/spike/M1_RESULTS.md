# RATL M1 — Alignment quality frozen

**Date:** 2026-08-18 · **Days to Oct 10:** 53
**Training:** none · **Downloads:** none · **Bench/threshold:** unchanged
**Gate result: G2 0.8395 — FAIL by 0.0105**

---

## 1. Progression, all on RATL-Bench-v0

| stage | G2 | start | end |
|---|---|---|---|
| wav2vec2 (rejected) | 0.5611 | 0.6820 | 0.4403 |
| Exp 1 — whisper-tiny+CTC, 6 h | 0.6776 | 0.7882 | 0.5670 |
| Exp 1b — 30 h | 0.6707 | 0.8079 | 0.5335 |
| Exp 3 — M2 separator | 0.7828 | 0.7999 | 0.7656 |
| M1 — + overlap windowing | 0.7889 | 0.8108 | 0.7671 |
| **M1 — + edge-aware rule** | **0.8395** | **0.8705** | **0.8086** |

**+0.1619 over Exp 1 with zero additional training and zero additional data.**

---

## 2. Method discipline

`RATL-Val-v0` was built for this stage: 552 held-out items (420 short, 132 long,
1.84 h) drawn only from the **6 reciters the exp1 checkpoint never trained on**,
bench ayat excluded, built entirely from cached audio.

All three decode factors — windowing, boundary rule, latency offset — were
selected on RATL-Val-v0. The bench was run **once per selected configuration**.
Held-out val G2 for the final config: **0.8787**.

---

## 3. Two of my hypotheses were falsified

**Long-form windowing was NOT a major cause.** Predicted as a main driver in
EXP3_RESULTS.md §6. Measured effect on held-out long items: **+0.0031**.
Overlap-and-discard was kept because it is free and slightly positive, not
because it mattered.

**The latency offset was NOT real.** A ~30 ms uniform late bias was attributed to
the encoder receptive-field centre. On held-out data **every non-zero offset was
worse**; 0 ms won. Not applied.

---

## 4. What actually mattered — a bug in my own M2 rule

M2_separator anchors word boundaries to the trained `|` separator spikes. But
separators exist only **between** words. At an ayah edge M2 fell back to frame 0
and frame T, so the first and last word of every ayah absorbed all leading and
trailing silence:

| | n | acc | median err_start | median err_end |
|---|---|---|---|---|
| Interior words | 1099 | **0.8499** | +40 ms | +20 ms |
| First word | 150 | 0.5600 | **−400 ms** | +20 ms |
| Last word | 150 | 0.5167 | +30 ms | **+550 ms** |

Interior words were already at the gate. **21% of boundaries were being destroyed
by a fallback branch.**

`M2E_edge_spike`: use the separator where one exists; fall back to the CTC spike
where none does. Held-out 0.8094 → 0.8787. Bench 0.7889 → **0.8395**.

An earlier hypothesis that leading basmala audio caused the ayah-1 failures was
tested and **falsified** — measured leading silence is 0.03–1.30 s, not the 4–8 s
a basmala would add. The ayah-1 clips failed because they are short, so edge
words dominate their word count.

---

## 5. Where the remaining 0.0105 is — Mujawwad, and only Mujawwad

| speed class | G2 | vs 0.85 |
|---|---|---|
| moderate | 0.8899 | ✅ |
| medium | 0.8736 | ✅ |
| measured | 0.8641 | ✅ |
| fast | 0.8582 | ✅ |
| **very_slow (Mujawwad)** | **0.7082** | ❌ |
| **excluding Mujawwad** | **0.8715** | ✅ |

**Four of five classes pass. Mujawwad is 20% of the bench and the entire deficit.**

Mujawwad detail: start acc 0.7584, end acc 0.6580, |err| p50 60 ms but p90
1657 ms on ends — a small number of catastrophic clips, not a broad shift.
Only 6 of 30 Mujawwad clips reach 0.85; the worst are 36:1, 93:1, 103:1, all
0.00, all short ayat with heavy elongation.

Rescuing the 6 worst clips overall would give ≈ 0.8472; the 9 worst ≈ 0.8483.
**The gate is now a small-N tail problem, not a systematic accuracy problem.**

Global tail: median 40 ms, p90 **330 ms** (was 630), p99 5536 ms, max 16520 ms.

---

## 6. Status against SPEC

| Gate | Threshold | Measured | Result |
|---|---|---|---|
| G1 size | ≤ 120 MB | 10.07 MB | ✅ PASS |
| **G2 boundary** | **≥ 0.85** | **0.8395** | ❌ **FAIL by 0.0105** |
| G5 RTF (est.) | ≤ 0.5 | 0.061–0.102 | ✅ PLAUSIBLE |

**The 0.85 threshold was not modified at any point.**
