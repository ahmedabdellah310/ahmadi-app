# RATL Week 1 — Day 3 Notes: G2 FORCED-ALIGNMENT FAILURE

**Date:** 2026-08-14
**Gate:** G2 — word-boundary accuracy on continuous recitation
**Result:** ❌ **FAIL**

---

## 1. G2 result

| | |
|---|---|
| **Measured** | **56.11%** |
| **Threshold** | **≥ 85%** within ±150 ms |
| **Result** | ❌ **FAIL** — 28.9 points short |
| Clips | 150 (30 ayat × 5 reciters) |
| Words compared | 1,374 |
| Boundaries compared | 2,748 (start + end) |

**RATL-Bench-v0** — assembled and reproducible (`spike/corpus/ratl-bench-v0.json`):

| Source | Provenance |
|---|---|
| Ground truth | cpfair/quran-align, release-2016-11-24, **CC BY 4.0** |
| Audio | everyayah.com per-ayah mp3, **CC BY 4.0**, Tarteel AI |
| Transliteration | quran.com API v4 word-level — **no Buraaq data used anywhere** |

---

## 2. Alignment error distribution

| Statistic | Value |
|---|---|
| Median absolute error | **124.1 ms** |
| Mean absolute error | 470.9 ms |
| p75 | 388.2 ms |
| p90 | **1,024.7 ms** |
| p95 | 2,025.2 ms |
| p99 | 5,986.6 ms |
| Max | **13,204.7 ms** |
| Mean **signed** error | +19.3 ms |
| Median **signed** error | +2.5 ms |

**The distribution is heavy-tailed, not uniformly bad.** The median boundary
(124 ms) is *inside* the ±150 ms tolerance. The failure is driven by a long tail
in which alignment collapses entirely — a p99 of ~6 s and a maximum of 13.2 s
mean whole passages lose lock.

**The near-zero signed error (+2.5 ms median) is important in the other
direction: it validates the ground truth.** There is no systematic offset
between quran-align timings and our predictions, so the ±150 ms tolerance in
`SPEC.md` §7.2 does not need renegotiating (§3.1 rule 4 is not triggered). The
errors are variance, not bias. **The ground truth is not the problem.**

---

## 3. Systematic failure analysis

### 3.1 Word ends are far worse than word starts

| Boundary | Accuracy |
|---|---|
| Start | **68.2%** |
| End | **44.0%** |

This is the direct, measurable consequence of the Day 2 finding that the model
**never emits `|` or space**. It has no learned representation of where a word
*stops*, because it was trained on isolated words where the end was always the
end of the file. Token activations bleed past word ends.

### 3.2 Drift accumulates through the ayah

| Position | Accuracy | Median abs err |
|---|---|---|
| First word of ayah | **70.3%** | 60.0 ms |
| All later words | **54.4%** | 104.9 ms |

Alignment starts well and degrades. This is drift, not random error.

### 3.3 Recitation style dominates everything

| Speed class | Reciter | Accuracy | Median abs err |
|---|---|---|---|
| moderate | Alafasy | **67.7%** | 81.2 ms |
| measured | Husary | 61.1% | 81.7 ms |
| medium | Minshawy Murattal | 59.6% | 80.5 ms |
| fast | Hani Rifai | 57.6% | 104.2 ms |
| **very_slow** | **Abdul Basit Mujawwad** | **34.0%** | **175.3 ms** |

Mujawwad — with long melodic elongation (madd) — collapses to 34%. A model
trained on isolated words has no concept of a vowel sustained for two seconds.
**No style reaches the 85% gate. The best case is 67.7%, still 17 points short.**

### 3.4 Utterance length

| Length | Accuracy |
|---|---|
| ≤ 25 s | **77.4%** |
| > 25 s | 58.7% |

Confirms the Day 2 length-degradation finding. Even short clips fall short of 85%.

### 3.5 ⚠️ `q` and `ḥ` are NOT the problem — correcting the Day 2 hypothesis

| Subset | n | Accuracy | Median abs err |
|---|---|---|---|
| Words containing `q` | 100 | 55.5% | 108.2 ms |
| Words without `q` | 1,274 | 56.2% | 96.5 ms |
| Words containing `ḥ` | 105 | **62.9%** | **78.8 ms** |
| Words without `ḥ` | 1,269 | 55.6% | 100.8 ms |

Day 2 predicted that the free-decode `q`→omission and `ḥ`→f/k confusions would
hurt G3/G4. **They do not affect alignment at all.** `q` words are within noise
of the mean; `ḥ` words are *better* than average.

This confirms the Day 2 reasoning that forced alignment is more forgiving than
free decoding — the model's posteriors do carry `q` and `ḥ` information even
when greedy decoding discards it. **The free-decode symptoms were the wrong
diagnostic.** The real defects are word-end modelling, drift, and elongation.

### 3.6 Text pipeline is clean — a genuine positive

**OOV characters: 0** across all 1,374 words.

The quran.com API v4 transliteration convention maps **perfectly** onto the
model's 38-token vocabulary — every character of every word resolved, no `[UNK]`
substitutions. The Uthmani → token pipeline is not a risk, and it works **without
any Buraaq-derived data**, which also permanently resolves the §4 licensing
escalation for the text path.

---

## 4. Gate classification under SPEC §7.5

G2 = 56.11%, i.e. **below the 60% PIVOT-acoustic band**.

**This is NOT yet a declarable STOP.** The STOP condition in `SPEC.md` §7.5 is
"G2 < 60% **after §7.4 ladder rungs 1–2**". Rungs 1 and 2 have not been run.
The correct current state is:

> **G2 FAIL → enter §7.4 contingency ladder.**
> This acoustic model is **not viable as the RATL foundation** and is not
> approved for production.

Per instruction, **no further gates (G3–G7) were run against this model.** There
is no value in measuring error-detection quality on top of an alignment layer
that loses lock on 44% of boundaries.

### Keeping the four feasibility questions separate

| Question | Verdict |
|---|---|
| **int8 feasibility** | ✅ **Proven.** 116.28 MB, 99.2% argmax agreement, posterior MAE 0.0005, int8 faster than fp32. Quantization is not implicated in any failure. |
| **Forced-alignment feasibility** | ✅ **Method proven, implementation sound.** CTC Viterbi alignment works; near-zero signed error shows the harness and ground truth are correct. |
| **This model's feasibility** | ❌ **Failed.** 56.11% vs 85%. |
| **RATL product viability** | ⚠️ **Undetermined.** Nothing measured so far rules out RATL. What has been ruled out is *this* acoustic model. |

The distinction matters: we built and validated the measurement apparatus, the
corpus, the ground truth, and the text pipeline. Only the acoustic model failed.

---

## 5. Required capability for the next candidate

The evidence specifies what the next model must have:

1. **Trained on continuous recitation, not isolated words.** This is the root
   cause of §3.1 (word ends) and §3.2 (drift).
2. **Explicit word-boundary modelling** — either emits a word delimiter/space, or
   is a phoneme model with a real inventory plus an explicit separator.
3. **Style and tempo robustness, specifically elongation (madd).** Mujawwad at
   34% is disqualifying; RATL users will recite slowly.
4. **Stability over long utterances**, or RATL chunks the audio — which is
   required anyway (Day 2 §3).
5. **Faster.** Desktop int8 RTF on this bench was **0.331** single-threaded
   (worse than the 0.139 parity figure, which used shorter clips). Projected to
   a Dimensity 1080 at 3–5×, that is RTF ≈ 1.0–1.65 against a ≤ 0.5 G5 ceiling.
   **G5 would very likely have failed too.**

### Candidate rungs (§7.4, all $0)

- **Rung 1 — inference retuning, chiefly chunking.** Median error is already
  inside tolerance and the failure is tail/drift, so VAD- or word-window-chunked
  inference with per-chunk re-anchoring is the highest-value cheap experiment.
  It attacks §3.2 and §3.4 directly. It cannot fix §3.1 (word ends), so it is
  unlikely to reach 85% alone.
- **Rung 2 — swap the acoustic model.** Preferred candidates are Arabic/Quranic
  **CTC models trained on continuous speech**, e.g.
  `jonatasgrosman/wav2vec2-large-xlsr-53-arabic` (license to verify) or a
  HuBERT/mHuBERT CTC of the kind used as the IQRA 2026 baseline.
  **Strategic note:** an Arabic-script CTC model would let RATL build target
  token sequences directly from Uthmani/Imlaei text, deleting the
  transliteration layer *and* the licensing question permanently. It also
  costs size/speed, which conflicts with G1/G5 — the small-tier plan becomes
  more urgent, not less.
- **Rung 3 — fine-tune on continuous recitation.** `everyayah` (CC BY 4.0) audio
  with quran-align (CC BY 4.0) word boundaries is a ready-made supervised
  continuous-speech corpus with word-level labels. Free-tier Kaggle/Colab.
  **This is now materially more justified than it was on Day 1** — §7.4 rung 3's
  evidentiary trigger has been met by measurement, not assumption.

---

## 6. Upstream data defect (recorded, per SPEC §3.1 rule 3)

`Abdurrahmaan_As-Sudais_192kbps.json` in the quran-align CC BY 4.0 release is
**corrupt**: ~154 KB of alignment-tool crash logs are prepended before the JSON
array (`Crashed Command '['./align', ...`), with the array beginning at byte
offset 154139. The JSON is recoverable by slicing, but the crash indicates the
timings are the output of a partially failed run.

**Handling:** excluded, not patched. `Hani_Rifai_192kbps` substituted as the
fast-tempo reciter. The loader now validates every ground-truth file and refuses
to proceed on malformed input. Worth reporting upstream.

The other 11 files loaded cleanly at 6,236 ayat each.

---

## 7. Status

| Gate | Result |
|---|---|
| G1 — int8 size | ✅ PASS (116.28 MB / 120 MB) |
| **G2 — boundary accuracy** | ❌ **FAIL (56.11% / 85%)** |
| G3, G4 — error detection | **Not run** — blocked by G2 |
| G5, G6 — on-device | **Not run** — projected to fail (RTF ≈ 1.0–1.65 / 0.5) |
| G7 — WASM | Not run (non-blocking) |

**Assets that survive and carry forward regardless of model choice:**
RATL-Bench-v0 corpus · quran-align ground truth integration · CTC forced-alignment
harness · the metrics suite · the Uthmani→token text pipeline (0 OOV) · the
ONNX/int8 export and quantization pipeline.

No SPEC threshold was altered. No result was rationalized.
