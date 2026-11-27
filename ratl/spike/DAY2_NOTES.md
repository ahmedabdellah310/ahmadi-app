# RATL Week 1 — Day 2 Notes

**Date:** 2026-08-13
**Scope:** ONNX export, int8 quantization (**G1**), fp32/int8 parity on real
continuous audio, licensing escalation.

---

## 1. G1 — int8 model size

| | |
|---|---|
| **Measured** | **116.28 MB** |
| **Threshold** | ≤ 120 MB |
| **Result** | ✅ **PASS** |
| **Margin** | **3.72 MB (3.1%)** |

Supporting figures: 94,402,472 params (94.4M, matches model card) · PyTorch fp32
378 MB · ONNX fp32 360.32 MB · compression 3.1× · export 5.7 s · quantize 20.1 s ·
**no export or quantization errors**, exit 0.

Quantization: dynamic, weight-only, `QUInt8`, `MatMul` only, `MatMulConstBOnly`.

**Architectural meaning.** G1 passes on a 3.1% margin. That is not comfortable.
Any of: quantizing fewer op types, needing fp32 fallback for accuracy-critical
layers, or bundling a second model would breach the ceiling. The v1.1 "small
tier" (20–35 MB) is therefore **not** a nice-to-have — it is the realistic answer
for the web target and for users on metered mobile data. 116 MB is a heavy
one-time download.

**Incidental:** tokenizer reports **40** tokens vs 38 in `vocab.json` (two added
specials). Blank/pad id **37** confirmed — required for CTC alignment.

**Frame rate: 49.75 fps ≈ 20.1 ms/frame.** Against G2's ±150 ms tolerance that is
±7.5 frames of slack. The model's time resolution is comfortably finer than the
gate. This is a genuine positive for alignment.

---

## 2. fp32 vs int8 parity — quantization is safe

10 clips, 5 reciters, 2.87 s – 67.89 s, continuous ayah-level recitation.
Audio from everyayah.com (origin of `tarteel-ai/everyayah`, CC BY 4.0;
attribution: Tarteel AI). Spike/research use; **RATL redistributes no audio.**

| Metric | Value |
|---|---|
| Mean frame argmax agreement | **99.215%** |
| Min frame argmax agreement | 97.83% |
| Mean posterior MAE | **0.000467** |
| Mean logit MAE | 0.1227 |
| Mean top-1 confidence fp32 → int8 | 0.98533 → 0.98516 |
| Mean decode CER (fp32 vs int8) | 4.87% |
| Max decode CER | 14.66% |
| Identical decodes | 3 / 10 |
| Desktop RTF, 1 thread, fp32 → int8 | 0.177 → **0.139** |

**SPEC v0.2 defines no numeric parity threshold.** This is a measurement, not a
gate, and no threshold is being invented retroactively. **Proposed for owner
approval as a spec amendment:** frame argmax agreement ≥ 99.0% and posterior
MAE ≤ 0.001. Measured values clear both.

**Conclusion: int8 quantization is safe for RATL.** RATL consumes **frame
posteriors**, not decoded text. Posterior MAE of 0.0005 and 99.2% argmax
agreement mean quantization is not a meaningful error source. The 4.87% decode
CER is greedy-CTC boundary brittleness on top of an already-degraded decode
(§3), not quantization damage. int8 is also **faster** than fp32 (0.139 vs 0.177
RTF).

---

## 3. ⚠️ PRINCIPAL FINDING — free-decode quality degrades badly on continuous recitation

Decoded transliterations, fp32:

| Clip | Expected (approx.) | Model output |
|---|---|---|
| 36:1 (5.2 s) | `yāsīn` | `yāsīna` ✅ |
| 2:1 (9.9 s) | `alif lām mīm` | `alih'lammem` ≈ ✅ |
| 1:1 (5.1 s) | `bis'mi l-lahi l-raḥmāni l-raḥīmi` | `bismillāhiraf'māni'rakīnma` ⚠️ |
| 112:1 (3.2 s) | `qul huwa l-lahu aḥadun` | `ulūmāwaḥad` ❌ |
| 114:1 (3.5 s) | `qul aʿūdhu birabbi l-nāsi` | `ul-aʿūbirariāsi` ⚠️ |
| 2:255 (67.9 s) | Ayat al-Kursi | near-gibberish ❌ |

### Observed failure modes

1. **No word segmentation at all.** The model emits neither the `|` delimiter
   (id 35) nor space (id 0) in continuous recitation. Output is one run-on
   string. Consistent with training on *isolated* words, where a delimiter was
   never needed.
2. **Quality collapses with utterance length.** 3–5 s clips are recognizable;
   the 68 s clip is unusable. This is exactly the isolated-word →
   continuous-speech generalization risk named in `SPEC.md` §2.1, now observed
   rather than hypothesized.
3. **Systematic phoneme confusion on ḥ** — `raḥmāni` → `raf'māni`,
   `raḥīmi` → `rakīnma`, `raḥmāna` → `rakmāna`. ḥ → f/k, repeatedly.
4. **Initial `q` dropped** — `qul` → `ul` in both 112:1 and 114:1, despite `q`
   being in the vocabulary.

### What this does and does not mean

**It does NOT mean G2 fails.** G2 measures **forced alignment**, where the model
is *given* the correct token sequence and need only decide *when* each token
occurs. Forced alignment is far more forgiving than free decoding: posteriors
that peak at roughly the right times produce good alignments even when
free-decode output is poor. The Day 3 measurement is the actual test and it has
not been run.

**It IS a serious negative indicator.** Free-decode degradation of this severity
lowers the prior on G2 passing, and failure modes 3 and 4 (systematic phoneme
confusion) will directly hurt G3/G4, because a `SUBSTITUTION` verdict depends on
the model distinguishing a genuinely wrong word from its own ḥ→f confusion.
**Failure mode 4 is especially concerning for false rejects (G4):** a reciter who
correctly says `qul` may be scored as having omitted it.

### Architectural consequences (independent of the G2 outcome)

- **RATL must chunk audio.** Quality collapse at 68 s means whole-passage
  inference is not viable. VAD- or ayah-segmented chunking becomes a
  requirement of the design, not a tuning option.
- **Chunking is doubly required for speed.** Desktop int8 RTF rose 0.11 → 0.18
  from 5 s to 68 s inputs — wav2vec2 self-attention is O(n²). Long inputs are
  both slower and worse.
- **G5 risk, early.** Desktop int8 RTF is 0.139 single-threaded. A Dimensity
  1080 Cortex-A78 is plausibly 3–5× slower than this desktop core, projecting
  RTF ≈ 0.42–0.70 against a **≤ 0.5** ceiling. **G5 is genuinely at risk** and
  chunking will matter for it. Measured on device Day 5; this is a projection,
  not a result.

---

## 4. Licensing escalation — `Buraaq/quran-md-words`

| Checked | Result |
|---|---|
| Repo files | No LICENSE file |
| README | No license terms; citation only |
| arXiv 2601.17880 (Quran-MD, Salman et al. 2026) | **Paper** is CC0; **dataset license unstated** |
| Transliteration provenance | **None documented** |

Under `SPEC.md` §1 (Apache-2.0 / MIT / CC BY 4.0 only), a lookup table derived
from this dataset **cannot be redistributed in the RATL SDK**. Research use
during the spike is unaffected.

**Status: unresolved, mitigation identified.** QUL publishes **"English wbw
Transliteration"** (word-by-word, JSON + SQLite). Buraaq's forms `l-ṣirāṭa` and
`al-raḥmāni` show the distinctive quran.com/QUL assimilated-article handling
plus the macrons and underdots our vocabulary needs, so a common lineage is
plausible. **Day 3 task:** locate the resource, verify its licensing and
provenance, and run a token-level exact-match comparison against a Buraaq
sample. Not shippable until licensing is explicitly resolved.

---

## 5. Day 2 status

| Task | Status |
|---|---|
| ONNX export | ✅ no errors |
| int8 quantization | ✅ no errors |
| **G1** | ✅ **PASS** — 116.28 MB / 120 MB |
| fp32-vs-int8 parity on real continuous audio | ✅ measured; quantization safe |
| Free-decode quality inspection | ⚠️ **negative indicator, §3** |
| Buraaq licensing | ❌ **blocked for redistribution; mitigation pending** |
| `RATL-Bench-v0` assembly | ⏭ next |
| QUL wbw transliteration comparison | ⏭ Day 3 |

**Gate outcomes to date: G1 PASS. G2–G7 unmeasured.**
Nothing so far triggers a `SPEC.md` §7.5 outcome. The Day 3 forced-alignment
measurement (G2) remains decisive, and §3 above means it should be approached
with a *lower* prior than at the start of Day 2.

### Note on a discarded approach

HF `datasets` streaming of `tarteel-ai/everyayah` was abandoned: the test split
is one of 13 multi-GB parquet shards, "streaming" still pulls whole row groups,
and the CDN timed out repeatedly. Replaced with direct per-ayah fetches from
everyayah.com (80 KB – 1 MB per file). This is also the right source for
`RATL-Bench-v0`, since QUL segment data is keyed to these same reciters.
