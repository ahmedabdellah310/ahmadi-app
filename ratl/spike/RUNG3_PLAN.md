# RATL — Rung 3 Candidate & Training Plan

**Date:** 2026-08-14
**Trigger:** G2 FAIL (56.11% vs 85%) — `DAY3_NOTES.md`
**Constraint:** SPEC v0.2 unchanged. G1 ≤ 120 MB, G2 ≥ 85%, G5 ≤ 0.5 RTF.

Every claim below is tagged **[MEASURED]**, **[ESTIMATED]**, **[ASSUMPTION]**, or
**[SPEC]**.

---

## 1. What training labels the corpus can yield **[MEASURED]**

From quran-align (CC BY 4.0) + everyayah audio (CC BY 4.0), 11 usable reciters
(Sudais excluded — corrupt upstream file):

| | |
|---|---|
| Ayah recordings | **68,596** |
| Word instances | **848,723** |
| Total audio | **358.2 hours** |
| **Clean pool** (zero align errors, RATL-Bench-v0 ayat removed) | **185.01 h · 50,234 ayat · 501,929 words** |

Per-reciter alignment cleanliness and word-duration spread **[MEASURED]**:

| Reciter | Hours | Clean % | word p50 | word p95 |
|---|---|---|---|---|
| Saood ash-Shuraym | 17.29 | **88.9%** | 720 ms | 1,500 ms |
| Abdul Basit Murattal | 28.40 | 84.9% | 1,150 | 2,380 |
| Minshawy Murattal | 26.66 | 84.4% | 1,080 | 2,280 |
| Hani Rifai | 24.23 | 83.5% | 1,010 | 2,170 |
| Mohammad al Tablaway | 27.69 | 82.0% | 1,070 | 2,780 |
| Husary | 37.83 | 81.7% | 1,510 | 3,120 |
| Alafasy | 28.41 | 73.8% | 1,070 | 2,990 |
| Husary Muallim | 39.28 | 67.3% | 1,310 | 2,530 |
| Abu Bakr Ash-Shaatree | 24.44 | 65.6% | 940 | 2,350 |
| Minshawy Mujawwad | 51.63 | 52.5% | 1,920 | 4,170 |
| **Abdul Basit Mujawwad** | 52.32 | **44.9%** | **1,920** | **4,860** |

**Inter-word gap: p50 = p90 = 10 ms [MEASURED].** quran-align emits contiguous
segments, so `end(word_i) = start(word_i+1) − 10 ms`. **Consequence:** word ends
are *derived*, not independently annotated. Word **starts** are the primary
timing signal, and boundary supervision should target starts.

**Derivable labels:**
1. **CTC token sequences** per ayah — needs no timing. Available for all 50,234 clean ayat.
2. **Explicit word-separator token** `|` inserted between words — directly attacks the measured 44.0% word-end failure.
3. **Frame-level word-start targets** from segment starts — for an auxiliary boundary head.
4. **Duration priors per token** — usable later for lattice constraints.
5. **Madd/elongation coverage** — Mujawwad p95 word duration is **4,860 ms**. That is the regime that collapsed to 34.0%.

**Held out, absolutely:** all 30 RATL-Bench-v0 ayat across **all** reciters, so
every candidate is evaluated under identical conditions (user requirement 5).

---

## 2. Modelling approach — CTC evaluated against alternatives

RATL's actual requirements: frame-synchronous output (word timings) · token
identity (substitution detection) · streaming · small and fast · robust to 4.9 s
elongated words · **explicit word-end modelling**.

| Approach | Timing | Token ID | Streaming | Size | Verdict |
|---|---|---|---|---|---|
| **CTC + explicit `\|` separator** | ✅ native Viterbi | ✅ | ✅ | ✅ smallest | **Selected** |
| RNN-T / transducer | ⚠️ harder to extract | ✅ | ✅ | ❌ larger | Rejected — open-vocab decoding we don't need |
| Attention seq2seq (Whisper decoder) | ❌ unreliable | ✅ | ❌ autoregressive | ❌ | Rejected |
| Frame boundary classifier alone | ✅ | ❌ | ✅ | ✅ tiny | Rejected alone — no substitution detection |
| **CTC + auxiliary boundary head** | ✅✅ | ✅ | ✅ | ✅ | **Held in reserve (Experiment 2)** |

**CTC is confirmed, not assumed.** Its known weakness — conditional independence
between output frames, i.e. a weak implicit language model — **does not apply to
RATL**, because the target text is always known. We never do open-vocabulary
decoding. That removes the main reason to prefer a transducer.

**Target units:** transliteration characters, unchanged. **[MEASURED]** 0 OOV
across 1,374 words via quran.com API v4. Holding this constant isolates the
model as the only changed variable.

---

## 3. Candidate feasibility screen

### 3.1 wav2vec2 family — eliminated **[MEASURED]**

| Candidate | Params | int8 MB | Desktop RTF | G5 projected **[ESTIMATED]** |
|---|---|---|---|---|
| w2v2-base L12 | 94.40M | 115.82 | 0.367 | 1.10–1.84 ❌ |
| w2v2-base L8 | 66.05M | 88.71 | 0.293 | 0.88–1.47 ❌ |
| w2v2-base L6 | 51.88M | 75.15 | 0.269 | 0.81–1.35 ❌ |
| w2v2-base L4 | 37.70M | 61.60 | 0.222 | 0.67–1.11 ❌ |

Cutting 94.4M → 37.7M params (2.5×) improved RTF only 1.65×. **The raw-waveform
convolutional frontend is a fixed dominant cost** — ~4.2M params but heavy
compute at any depth. **No wav2vec2 depth plausibly reaches G5.** The family is
eliminated on deployment grounds before accuracy is even considered.

This also retroactively explains the failed model: it was never going to pass G5
either (bench RTF 0.331 → projected 0.99–1.66).

### 3.2 Mel-input architectures — viable **[MEASURED]**

| Candidate | Params | int8 MB | Desktop RTF | G1 **[MEASURED]** | G5 **[ESTIMATED]** |
|---|---|---|---|---|---|
| **whisper-tiny-enc, 8 s window** | **7.80M** | **9.49** | **0.027** | ✅ | **0.081–0.135 PLAUSIBLE** |
| **whisper-tiny-enc, 15 s window** | 7.94M | 10.01 | 0.043 | ✅ | 0.129–0.215 PLAUSIBLE |
| whisper-base-enc, 15 s | 20.23M | 23.11 | 0.098 | ✅ | 0.294–0.490 MARGINAL |
| Zipformer streaming CTC 14M (pretrained probe) | ~14M | 20.62 | n/a | ✅ | not comparably measured |

Replacing the raw-waveform frontend with log-mel features is the whole
difference: mel is an FFT, not a deep conv stack.

**vs the failed model [MEASURED]:** whisper-tiny-enc is **11.6× smaller**
(116.28 → 10.01 MB) and **8.5× faster** (0.367 → 0.043 desktop RTF). At ~10 MB it
lands inside the SPEC v1.1 "small tier" (20–35 MB) rather than merely under the
G1 ceiling.

**Zipformer** is architecturally ideal (streaming-native) but its ONNX export
exposes **35 stateful cache inputs**. Integration cost is high for an 8-week
campaign. **Deferred to v2**, recorded as the strongest longer-term option.

### 3.3 Selected candidate

**`tarteel-ai/whisper-tiny-ar-quran` encoder + CTC head.** Apache-2.0.

Rationale beyond size and speed: that checkpoint was fine-tuned on **everyayah —
continuous, ayah-level Quranic recitation** — so its encoder has already seen
exactly the regime where the previous model failed, including madd. It reports
7.05% validation WER. **[ASSUMPTION]** the encoder's pretrained positional
embeddings survive truncation to a shorter window by slicing the first N
positions; verified in Experiment 1 setup, not yet proven.

**Fallback:** `whisper-base` encoder (20.23M) if tiny lacks capacity. Still G1-clean, G5 marginal.

---

## 4. Compute **[MEASURED]**

**NVIDIA RTX 4060 Laptop, 8188 MiB VRAM, driver 591.86, compute capability 8.9 — local.**

$0 constraint satisfied with no Kaggle/Colab queue limits. A 7.9M-parameter model
on 8 GB VRAM is comfortable at bf16 with duration-bucketed batches.

---

## 5. Experiment 1 — smallest falsifying test

**Hypothesis H1.** A CTC head on the Quran-adapted whisper-tiny encoder, trained
on continuous recitation with an explicit `|` word-separator token, materially
improves word-boundary accuracy over the measured baseline — in particular the
**44.0% word-end** figure.

**Deliberately excluded from Experiment 1:** the auxiliary frame-level boundary
head. If the `|` token alone fixes word ends, the timing-supervised head is
unnecessary complexity. It is Experiment 2, run only on failure.

| | |
|---|---|
| Data | ~6 h from the clean pool — 3 reciters: Husary (measured), Alafasy (moderate), **Abdul Basit Mujawwad (very_slow — the 34.0% failure case)** |
| Held out | all 30 RATL-Bench-v0 ayat, all reciters |
| Target | transliteration chars + `\|` between words |
| Loss | CTC only |
| Init | `tarteel-ai/whisper-tiny-ar-quran` encoder; CTC head random |
| Window | 15 s (covers all but the longest ayat; longer ayat chunked) |
| Eval | **`05_g2_alignment.py` unchanged**, model swapped — identical conditions |

### Falsification criteria — decided in advance

| Outcome | Reading |
|---|---|
| Word-end accuracy ≥ 70% **and** G2 ≥ 75% | H1 supported → scale data, pursue 85% |
| Word-end 55–70% | Partial → run Experiment 2 (boundary head) |
| Word-end < 55% (vs 44.0% baseline) | **H1 falsified** → whisper encoder is not the answer; escalate to Zipformer or reconsider V1 scope |

**No SPEC threshold is modified.** G2's bar remains 85%. The intermediate targets
above are decision rules for *this experiment*, not redefinitions of the gate.

**Cost:** $0. Local GPU. **[ESTIMATED]** ≤ 1 h/epoch for 6 h audio on a 7.9M model.

---

## 6. What is not being built

No production infrastructure. No RATL Core. No bindings. RATL-Bench-v0 and the
alignment harness are frozen so every candidate is compared identically.
