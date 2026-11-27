# RATL — V1 Technical Specification

**Spec version:** 0.4
**Status:** Approved
**Supersedes:** 0.3
**Target:** Itqan "Code Serves the Quran" campaign, 10 October 2026

> RATL is an open-source, on-device Quran recitation assessment engine that
> developers can integrate into their applications.

---

## Changelog

### 0.4 (approved)

Two decisions taken on measured evidence, both narrowing V1 scope deliberately.

- **§4 substitution.** V1 does not assert `SUBSTITUTION`. Measured: no
  configuration exists that both detects substitution and meets G4. Of 10,560
  seen-tier configurations, 768 met the false-reject budget and **none** retained
  substitution detection; the cheapest substitution-active configuration costs
  a held-out FRR near 0.08 against a 0.05 ceiling. V1 therefore returns
  `UNCERTAIN` where substitution is suspected. Further substitution research is
  out of scope for V1.
- **§1 / §5.5 Quran text.** RATL ships **no Quran text and no transliteration
  corpus**. Targets are built from text the integrator supplies, which §5.5
  already provides for. This removes the last runtime network dependency and
  keeps RATL clear of third-party text licensing.

### 0.3 (approved)

Reconciles the specification with the **measured** V1 implementation. No gate
threshold is changed. G2 remains ≥ 0.85 within ±150 ms over all words.

- **§2.1 acoustic model replaced.** `wav2vec2-quran-phonetics` was measured and
  **rejected**: G2 0.5611, and projected device RTF 0.99–1.66 against a 0.5
  ceiling. The §2.3(1) fallback was taken. V1 ships a fine-tuned
  `tarteel-ai/whisper-tiny-ar-quran` encoder + CTC head. The §2.1 load-bearing
  risk resolved as **realised**; §7 did its job.
- **§2.4 runtime replaced.** sherpa-onnx is not used. RATL calls ONNX Runtime
  directly and owns its forced aligner and boundary rule.
- **§6.1 core language: Rust → Kotlin/JVM for V1.** Rust + UniFFI deferred to
  v1.1, protected by the mandatory conformance vectors of §6.3.
- **§6.2 / §6.3** updated for a Gradle multi-module layout.
- **§3.2** records RATL-Val-v0 and the reciter-disjointness limits actually
  achievable from cached audio.
- **§5.6 / §4** de-Rust-ified to language-neutral wording.
- **§7.6 added** — measured gate outcomes.
- **§8** milestones replaced with the actual M1–M5 release plan.

**Amendments applied at approval:**
1. **Fine-tuning was exercised (§1, §7.4 rung 3) at $0** on a local RTX 4060,
   not on free-tier cloud GPU. The $0 constraint held.
2. **§6.1's own argument against deferred ports is accepted as a real cost.**
   The mitigation is that conformance vectors (§6.3) become blocking for V1.

### 0.2 (approved)
- Renamed throughout: project is **RATL**.
- Platform scope reduced to **Android-first** (§6). Emran is native Android/Kotlin.
- Ground-truth source corrected to **QUL segmented recitations** (§3.1). `everyayah`
  is ayah-level and carries no word boundaries; it cannot alone support §7/G2.
- G7 (WASM) reclassified **non-blocking** (§7).
- Fine-tuning restated as **evidence-gated, $0-only** (§1, §7.4).

**Amendments applied at approval:**
1. **Ground-truth authority (§3.1).** Expert-validated published data (QUL) is the
   source of truth wherever available. Manual listening is a *sanity check on the
   published data* only — it is never itself authoritative Quranic ground truth,
   and never overrides QUL. Where the two disagree, the disagreement is recorded
   and escalated, not resolved by ear.
2. **No conditional PASS (§7.5).** G1–G6 must all pass for a PASS. Marginal G3/G4
   results yield **HOLD**, not PASS.
3. Benchmark device fixed (§7.3).

### 0.1
- Initial draft.

---

## 0. Identity and scope

| Term | Meaning |
|---|---|
| **RATL** | the project |
| **RATL Core** | the engine — alignment, taxonomy, scoring. **Kotlin/JVM in V1**; Rust in v1.1 (§6.1) |
| **RATL SDK** | the developer-facing package (Core + bindings + models + docs) |
| **RATL v1** | first release, defined by this specification |

**V1 does exactly one thing:** given a *known* target passage and a user's
recitation of it, return a structured, per-word assessment — fully on-device.

**In scope for V1**
- Known target ayah / passage
- Word-level alignment of recited audio to target text
- Detection of omission, repetition, insertion, incompletion
- Substitution **surfaced as `UNCERTAIN`, not asserted** (§4.1)
- Explicit abstention when confidence is insufficient
- Streaming and batch operation
- Typed, structured results
- Apache-2.0

**Out of scope for V1**
- Asserting `SUBSTITUTION` as a verdict (§4.1)
- Tajweed diagnosis (madd, ghunnah, qalqalah, ikhfa, …)
- Phoneme-level mispronunciation detection
- Open-vocabulary "which ayah is this?" identification
- Reciter identification
- Grading, scoring, or gamification UX
- Any server component

### 0.1 Why tajweed is excluded (normative)

The IQRA 2026 organizer baseline for phoneme-level mispronunciation detection
scores **F1 = 0.4414** (mHuBERT-94M frozen encoder + 2-layer BiLSTM, CTC loss).
Phoneme-level diagnosis is an open research problem at roughly coin-flip quality.
Shipping a tajweed verdict at that quality in a religious application is worse
than shipping nothing.

RATL v1 must state this limitation plainly in its own README. Silence about the
limit is a specification violation.

---

## 1. Constraints

| Constraint | Requirement |
|---|---|
| External infrastructure spend | **$0**, including every contingency path |
| Cloud inference | Prohibited in V1 |
| User-supplied AI API keys | Prohibited |
| User accounts / telemetry | Prohibited |
| Runtime network access | One-time model download only; fully offline thereafter |
| Quran text | **Supplied by the integrator (§5.5).** RATL ships no Quran text and no transliteration corpus |
| Licenses | Apache-2.0 / MIT / CC BY 4.0 only, verified per artifact |
| Fine-tuning | **Disabled by default**; enabled only by Week 1 evidence (§7.4), and only on free-tier GPU |

A `RemoteAcousticModel` **interface** is defined so downstream integrators *may*
supply server-side or NPU inference. **No implementation ships in V1**, and the
SDK must never require one.

---

## 2. Models and runtime

### 2.1 Primary acoustic model — `RATL-Align-v1`

Fine-tuned from `tarteel-ai/whisper-tiny-ar-quran` (Apache-2.0).

| Property | Value |
|---|---|
| License | Apache-2.0 |
| Base | `tarteel-ai/whisper-tiny-ar-quran` **encoder only** |
| Positional embeddings | sliced 30 s → 15 s (`max_source_positions = 750`) |
| Parameters | 7,933,859 |
| Objective | CTC + auxiliary boundary BCE (training only) |
| Vocabulary | 35 tokens: `<blank>` + `\|'-abdefghijklmnoqrstuwyzāīūʿḍḥṣṭẓ` |
| Word separator | explicit `\|` token in the CTC target |
| Input | 80-bin log-mel, 100 fps, 15 s window |
| Output frame rate | 50 fps (20 ms/frame) |
| Training corpus | 30.0 h, 13,188 clips, 9 reciters (QUL/quran-align, CC BY 4.0) |

The auxiliary 2-channel boundary head (`P(start)`, `P(end)`) is a **training-time
objective only**. Its posteriors are measurably unusable as an inference-time
boundary source (pure DP scored 0.4844, near chance) but it sharpened the shared
encoder, lifting the CTC decoder from 0.8787 → 0.8941 on held-out data. **It is
not used at inference and may be dropped from the export.**

#### 2.1.1 Rejected primary — `TBOGamer22/wav2vec2-quran-phonetics` (normative record)

Spec 0.2 selected this model and flagged its isolated-word training as the
load-bearing risk. **The risk was realised.** Measured: G2 **0.5611**; free
decoding collapses on continuous recitation; and separately its raw-waveform
convolutional frontend is a fixed dominant cost giving a projected device RTF of
**0.99–1.66** against a 0.5 ceiling. Not fixable by fine-tuning. The §2.3(1)
fallback was taken. The whole wav2vec2 family is closed for V1.

### 2.2 Deployment form

ONNX export, then **int8 dynamic quantization** (weight-only on linear
operators; uint8 preferred for any future WASM target).

| Artifact | Budget | Hard ceiling |
|---|---|---|
| Primary model, int8 | **measured: see §7.6** | **120 MB** |
| Small tier (v1.1 stretch) | 20–35 MB | — |

The 120 MB ceiling is retained unchanged. The frozen model is roughly an order
of magnitude under it, which removes model size as a V1 risk.

### 2.3 Fallback ladder

All Apache-2.0, all $0. **Rung 1 was taken and is now §2.1.** Rungs 2–3 remain
available if a future gate regresses.

1. ~~`tarteel-ai/whisper-tiny-ar-quran`~~ — **taken; promoted to §2.1.**
2. `jonatasgrosman/wav2vec2-large-xlsr-53-arabic` — general Arabic CTC, larger.
   **License must be verified before adoption.**
3. Further fine-tuning — local GPU or free-tier only (§7.4). Note **data scaling
   is a closed avenue**: 6.0 h → 30.0 h improved validation CTC loss by 56% and
   made G2 *worse*. Data is not the binding constraint for boundary accuracy.

### 2.4 Inference runtime

**ONNX Runtime**, called directly (MIT).

| Target | Package |
|---|---|
| Android | `com.microsoft.onnxruntime:onnxruntime-android` |
| Desktop JVM | `com.microsoft.onnxruntime:onnxruntime` |
| Export / research | `onnxruntime` (Python) |

**sherpa-onnx is not used.** Spec 0.2 selected it for its existing wav2vec2 CTC
forced-alignment implementation; since the wav2vec2 family was rejected (§2.1.1)
and RATL's boundary rule (`M2E_edge_spike`) is not a stock aligner, that
rationale no longer holds. RATL owns its aligner. This removes a large native
dependency and keeps the V1 stack to one runtime library with a Java API on both
targets, with no native code authored by this project.

Model inference runs in ONNX Runtime's native C++ in every binding, so the
choice of core language does not affect the dominant cost.

---

## 3. Evaluation

The evaluation harness is a **mandatory component of V1**, not a supporting
tool. It is released standalone.

### 3.1 Ground truth (authority rules)

**QUL segmented recitations are the word-boundary ground truth.** QUL publishes
58 segmented audio resources with exported word-level timestamp data.

`tarteel-ai/everyayah` provides audio plus diacritized text but is **ayah-level
and carries no word boundaries**. It therefore cannot alone support G2 or any
boundary metric.

**Authority rules (normative):**

1. Expert-validated published data — QUL segment exports, Tanzil/QUL text — is
   the source of truth wherever available.
2. Manual listening is a **sanity check on the published data**, used to detect
   systematic offsets or corrupt exports. It is **not** authoritative Quranic
   ground truth and must never be used to relabel individual words.
3. Where manual review and published data disagree, the disagreement is
   **recorded and escalated**. It is not resolved by ear, and it is not silently
   corrected.
4. If published segment data proves systematically loose, the correct response is
   to **renegotiate the ±150 ms tolerance in this specification**, not to hand-fix
   the corpus.

### 3.2 Corpora

| Set | Construction | License | Role |
|---|---|---|---|
| `RATL-Bench-v0` | QUL segmented recitations; ≥5 reciters, ≥2 speeds, 30 ayat | CC BY 4.0 | **Week 1 probe** — boundary ground truth |
| `RATL-WordErr-v1` | programmatic mutation of segmented audio at known word positions | CC BY 4.0 | **Primary** — taxonomy metrics |
| `RATL-Clean-v1` | unmutated hold-out | CC BY 4.0 | **False-reject control** |
| QuranMB.v2 | IQRA 2026; 1,643 utterances, ~2.5 h | CC BY 4.0 | Secondary — real human audio sanity check |
| Iqra_Extra_IS26 | IQRA 2026; 1,333 real mispronunciations | CC BY 4.0 | Secondary, if obtainable |

**Mutation method** for `RATL-WordErr-v1`: delete a word; splice in a different
Quranic word; duplicate a word or phrase; truncate a word mid-utterance; insert
foreign speech. Because mutations are applied at known word positions in
segmented audio, **ground truth is exact by construction** — no annotation, no
GPU, $0, and regenerable from a seed.

Reciter-disjoint splits are mandatory: no reciter may appear in both tuning and
test. Attribution to Tarteel AI and QUL is required in all derived artifacts
(CC BY 4.0).

**Sets actually built (V1):**

| Set | Construction | Role | Status |
|---|---|---|---|
| `RATL-Bench-v0` | 30 ayat × 5 reciters = 150 clips / 1,374 words | **The gate.** Never tuned on | built |
| `RATL-Val-v0` | 552 items (420 short + 132 long), 1.841 h, 6 reciters | Held-out decode selection | built |
| `RATL-WordErr-v1` | seeded mutation of cached segmented audio | G3 + taxonomy metrics | see §7.6 |
| `RATL-Clean-v1` | unmutated hold-out | G4 / FRR control | see §7.6 |

**Disjointness — declared limits.** RATL-Val-v0 is reciter-disjoint from the
*exp1* checkpoint but **not** from the frozen model's 30 h corpus. Of the cached
audio, only `Hani_Rifai_192kbps` and `Minshawy_Murattal_128kbps` are unseen by
the frozen model, and only over the 30 bench ayat. Every corpus must therefore
state its disjointness status explicitly, and any published number computed on a
non-disjoint split must be labelled as such. Reciter-disjointness remains
mandatory for G4; how it is satisfied is recorded in §7.6.

**Primary (reciter-disjoint) and secondary (seen-reciter) results are reported
separately and are never merged into a single figure.**

### 3.3 Metrics

Per error class: **Precision, Recall, F1**.

Additionally:

- **False Reject Rate (FRR)** — a correct recitation flagged as an error.
  **Treated as the most serious failure mode in RATL.**
- True Accept Rate, False Accept Rate
- **Boundary accuracy** — % of words whose predicted start/end fall within
  **±150 ms** of ground truth
- Abstention rate
- Real-time factor (RTF) and peak RSS, per platform

### 3.4 Reporting rules

Every published claim cites dataset, split, model hash, and spec version.
No metric computed on tuning data may appear in the README.

---

## 4. Error taxonomy (normative)

Stable string identifiers. This enum is RATL's public contract and is versioned
with this specification.

| Identifier | Meaning |
|---|---|
| `CORRECT` | Word recited, aligned, above confidence threshold |
| `OMISSION` | Target word absent from the recitation |
| `SUBSTITUTION` | Different content spoken in this word's position |
| `REPETITION` | Word or phrase recited more than once consecutively |
| `INSERTION` | Extra content between target words, corresponding to no target word |
| `INCOMPLETE` | Word begun then truncated (audio ended, or reciter broke off) |
| `UNCERTAIN` | **Abstention** — below confidence threshold; RATL declines to judge |
| `PENDING` | Streaming only: target word not yet reached |

### 4.1 Substitution in V1 (normative)

`SUBSTITUTION` remains in the taxonomy and in the public enum. **V1 never emits
it.** Where the evidence indicates the target word was not what was recited, V1
returns `UNCERTAIN`.

This is a measured limitation, not an oversight. The substitution signal
available from the frozen acoustic model does not generalise across reciters at
a false-reject rate the specification permits:

| | measured |
|---|---|
| configurations swept (selection tier) | 10,560 |
| meeting FRR budget, coverage floor and G3 | 768 |
| of those retaining substitution detection | **0** |
| cheapest substitution-active configuration | held-out FRR ≈ 0.08 vs a 0.05 ceiling |

Returning `UNCERTAIN` is the behaviour §4 rule 1 already requires: RATL declines
to judge rather than accuse a reciter on evidence that cannot carry the verdict.

**Consumers must still handle `SUBSTITUTION` defensively** — it is a valid member
of the enum and a later version may emit it. Removing it would be a breaking
change; not emitting it is not.

The README must state this limitation plainly, as it must for tajweed (§0.1).

**Design rules (normative)**

1. **`UNCERTAIN` is a first-class outcome, not a failure.** RATL must be able to
   say "I don't know" rather than accuse a reciter of an error. Default
   confidence thresholds are tuned to **minimize FRR**, not to maximize F1.
2. **Out-of-order recitation is a session-level event, not a word status.**
   Jumps are handled by re-anchoring and surfaced as a `Reanchored` session event.
3. `REPETITION` and `INSERTION` are distinguished by whether the extra content
   matches the preceding target word.
4. Pauses, breaths, and hesitation are **never** errors.
5. Adding a status is a **breaking change**. Consumers must handle unknown values
   defensively; this obligation is documented in the SDK.

---

## 5. Public API (normative shape)

Language-neutral. Each binding renders it idiomatically. Signatures only — no
implementation is defined here.

### 5.1 Core types

```
TargetWord {
  index:    u32        // 0-based within the target
  qul_key:  string     // "surah:ayah:word" — QUL-compatible
  uthmani:  string
  imlaei:   string
  tokens:   [string]   // model-vocabulary tokens
}

Target {
  words:       [TargetWord]
  surah:       u16
  ayah_start:  u16
  ayah_end:    u16
}

WordResult {
  index:       u32
  qul_key:     string
  status:      WordStatus       // §4
  confidence:  f32              // 0.0 – 1.0
  t_start_ms:  u32 | null
  t_end_ms:    u32 | null
  observed:    string | null    // decoded tokens, when status != CORRECT
}

Assessment {
  words:              [WordResult]
  events:             [SessionEvent]
  audio_duration_ms:  u32
  model_id:           string     // model hash — required for reproducibility
  spec_version:       string
}
```

### 5.2 Engine

Loaded once, shared across sessions.

```
Engine.load(model_dir: path, opts: EngineOptions) -> Engine
Engine.assess(audio: PCM_f32_16k, target: Target, cfg: SessionConfig) -> Assessment
Engine.create_session(target: Target, cfg: SessionConfig) -> Session
Engine.model_info() -> ModelInfo
```

### 5.3 Session (streaming)

```
Session.push_audio(frames: PCM_f32_16k) -> PartialAssessment
Session.finish() -> Assessment
Session.reset(target: Target | null) -> void
Session.cursor() -> u32
```

`push_audio` accepts arbitrary chunk sizes; buffering is internal.

**`PartialAssessment` may revise previously emitted words. Results are
provisional until `finish()`.** This must be documented explicitly, since UIs
must not render early verdicts as final.

### 5.4 Configuration

```
SessionConfig {
  confidence_threshold:  f32     // default tuned for low FRR
  allow_reanchor:        bool    // default true
  max_skip_words:        u32     // alignment graph skip-arc limit
  emit_partials:         bool
  strictness:            Lenient | Balanced | Strict
}
```

### 5.5 Text normalization (public, independently usable)

```
Normalizer.tokenize(uthmani_text: string) -> [TargetWord]
Normalizer.build_target(surah, ayah_start, ayah_end, source: QuranSource) -> Target
```

Handles Uthmani ↔ Imlaei, diacritic policy, hamzat wasl, waqf marks, and
superscript alif, mapped onto QUL word IDs. Ships as a usable module in its own
right; several downstream projects need only this.

**Text-source policy (normative).** `QuranSource` is supplied by the caller.
RATL ships the tokenizer and the model vocabulary; it does **not** ship, bundle
or download Quran text, translations or transliterations. Every Quran
application already holds its own text, and this keeps RATL free of third-party
text licensing while satisfying the §1 prohibition on runtime network access.

Verified for this decision: `quran-align` word timings are **CC BY 4.0**
(confirmed from the distributed LICENSE file) and are used for evaluation only.
QUL/quran.com transliteration carries **no blanket redistributable licence** —
QUL states that resources vary in copyright status and must be checked per
resource — so it is used as a local evaluation input only and is never
redistributed. Evaluation caches are excluded from the published repository.

### 5.6 Extension point (defined, not implemented in V1)

```
interface AcousticModel {
  posteriors(audio: PCM_f32_16k) -> FramePosteriors
  vocabulary() -> [string]
}
```

**V1 satisfies this interface with an ONNX Runtime implementation.** The
interface is public so an integrator may substitute an NPU or server-side
posterior source; no such implementation ships in V1, and the SDK never
requires one.

---

## 6. Repository and platform scope

### 6.1 Core language

**V1: Kotlin/JVM. v1.1: Rust + UniFFI.**

RATL Core is **pure Kotlin with zero `android.*` dependencies**. The same
artifact runs on Android (`onnxruntime-android`) and on desktop JVM
(`onnxruntime`) — one core, two targets, no native code authored here, no NDK,
no cross-compilation, no JNI.

**Rationale, against the October 10 campaign deadline:**

- Model inference is ONNX Runtime C++ in every language (§2.4). RATL-authored
  code is feature extraction and a Viterbi lattice — a minority of a budget with
  roughly 5–8× headroom against G5. Core language is not a G5 discriminator.
- Rust + UniFFI adds toolchain install, NDK cross-compilation for 3–4 ABIs,
  native packaging inside an AAR, and a generated binding layer — estimated
  6–11 working days, whose failure modes are *blocking* rather than *slow*.
- The public API is expected to need revision on first contact with a real
  integrator. Revising Kotlin is an edit; revising a UniFFI surface is a
  regeneration cycle.
- A contributor needs only a JDK and Android Studio. This is directly load-
  bearing for the campaign's contributor-facing criteria.

**The cost is stated plainly and not minimised.** Spec 0.2 argued that deferred
ports do not happen in practice, and that argument is accepted as correct. A
JVM core makes the v1.1 JS/WASM and Python bindings materially harder, and RATL
may remain a JVM/Android SDK unless the Rust core is actually written.

**Two constraints bound that risk, and both are binding requirements of V1:**

1. **Conformance vectors (§6.3) are blocking for V1**, not optional. They are
   the executable specification a Rust core must satisfy.
2. RATL Core is kept free of platform dependencies and small — the algorithm is
   a bounded body of numeric code, not an open-ended port.

**Rust + UniFFI is deferred to v1.1, not removed.** A public `ratl-core-rust`
issue is filed at release, so the deferral is a visible, contributable work item
rather than a silent omission.

**Critical decoupling (unchanged from 0.2):** RATL Core owns *alignment only*.
Acoustic inference sits behind the `AcousticModel` interface (§5.6), satisfied
per platform by ONNX Runtime. This isolates the least-portable dependency from
the part that must be portable, and it is what makes the v1.1 Rust core a
contained rewrite.

### 6.2 Platform tiers

| Tier | Component | V1 status |
|---|---|---|
| 1 | RATL Core (Kotlin/JVM, no `android.*`) | **Ships** |
| 1 | Android AAR — same core + `onnxruntime-android` | **Ships** — Emran's platform |
| 1 | `ratl-eval` desktop JVM CLI | **Ships** — drives the harness without a binding |
| 1 | Conformance vectors | **Ships — blocking** (§6.3) |
| 2 | RATL Core in Rust + UniFFI | v1.1 |
| 2 | Python binding | v1.1 — via the Rust core |
| 2 | JS / WASM | v1.1 — via the Rust core |
| 3 | Swift, Dart | v1.2+ — via the Rust core |

A JVM eval CLI over the shipping core keeps 0.2's intent — the harness runs on
desktop with no additional binding — while removing the toolchain entirely.
Python remains a research, training and export tool. **No production RATL
implementation is written in Python.**

### 6.3 Layout

```
ratl/
├── SPEC.md
├── WEEK1_PLAN.md
├── ratl-core/                # Kotlin/JVM: alignment lattice, taxonomy, scoring
├── ratl-text/                # Kotlin/JVM: normalization, tokenization, QUL mapping
├── ratl-acoustic-ort/        # Kotlin/JVM: ONNX Runtime AcousticModel
├── ratl-eval/                # JVM CLI harness — released standalone
├── ratl-android/             # AAR: onnxruntime-android + asset loading
├── eval/
│   └── corpus/               # seeded RATL-WordErr generator
├── models/                   # Python export + quantization (no weights in git)
├── conformance/              # golden test vectors
├── examples/
│   └── android-demo/         # thin; proves the SDK, is not a product
├── spike/                    # PRESERVED research record — never refactored
└── docs/
```

**Conformance vectors are blocking for V1.** With Rust deferred (§6.1) they are
no longer only a convenience for future bindings — they are the mechanism that
makes the deferral safe. V1 does not ship without them.

`spike/` is the measured evidence base for every claim in the release record. It
is preserved verbatim, is not refactored into the production tree, and is
excluded from the published artifacts.

---

## 7. Week 1 feasibility gates

### 7.1 Purpose

Determine whether the selected acoustic model can handle **continuous** Quran
recitation well enough for RATL v1. Nothing else is built until this answers.

### 7.2 Gates

| # | Criterion | Threshold | Status |
|---|---|---|---|
| G1 | int8 model size | ≤ 120 MB | Blocking |
| **G2** | **Word-boundary accuracy, continuous recitation** | **≥ 85% within ±150 ms** | **Blocking — decisive** |
| G3 | Omission recall, 50-sample mutation probe | ≥ 0.80 | Blocking |
| G4 | False-reject rate, clean recitation | ≤ 0.05 | Blocking |
| G5 | RTF, benchmark device, single-thread CPU | ≤ 0.5 | Blocking |
| G6 | Peak RSS, benchmark device | ≤ 400 MB | Blocking |
| G7 | WASM loads and runs in browser | RTF ≤ 1.0 | **Non-blocking** |

**G2 is decisive.** It is the direct test of the isolated-word → continuous-speech
risk identified in §2.1.

**G7 is non-blocking** because web is not a V1 target (§6.2). Failing it must not
stop an Android-first V1. It is still measured — the result informs v1.1 planning
and costs roughly two hours.

### 7.3 Benchmark device

**Xiaomi Redmi Note 12 Pro+ 5G, Android 14.**

The exact RAM/storage variant, chipset, and build number are recorded in the
Week 1 report. All G5/G6 figures are reported against this device by name; no
figure may be reported against an emulator or a desktop CPU.

### 7.4 Contingency ladder

Every rung is **$0**.

1. Re-tune inference-side parameters (chunking, VAD, feature normalization) —
   hours, free.
2. Swap to fallback model §2.3(1) or §2.3(2) — free.
3. Fine-tune §2.1 on `everyayah` using **free-tier Kaggle or Colab GPU** — free.
4. Paid compute is **brought to the project owner as an explicit decision**, never
   taken as a default.

### 7.5 Decision rule

| Outcome | Condition | Action |
|---|---|---|
| **PASS** | **G1–G6 all pass** | Proceed to Week 2 unchanged. No fine-tuning. $0 spent. |
| **HOLD** | G1, G2, G5, G6 pass; G3 and/or G4 marginal or failing | **Not a PASS.** Work continues on investigation and on the alignment lattice, but RATL v1 is not declared viable until the actual V1 assessment path meets §7.2 thresholds. Re-gate at Week 4. Risk recorded explicitly in the report. |
| **PIVOT — runtime** | G1/G5/G6 fail while G2 passes | Model is correct but too heavy. Swap to `whisper-tiny-ar-quran` encoder or the small tier. Algorithm and schedule unaffected. |
| **PIVOT — acoustic** | G2 between 60% and 85%, or passes only for slow recitation | Model does not generalize. Run §7.4 rungs 1 → 2 → 3. Costs roughly one week of buffer. Fine-tuning becomes justified **here and only here**. |
| **STOP** | G2 < 60% after §7.4 rungs 1–2 | Continuous-recitation alignment is not reachable at this model scale in this timeframe. Escalate with two named fallbacks: (a) RATL v1 narrows to word-boundary alignment only, dropping error typing; (b) pivot to the evaluation harness and benchmark as the campaign deliverable — independently valuable, ~3 weeks. |

**There is no conditional PASS.** G1–G6 must all pass for a PASS. Marginal G3/G4
results yield HOLD.

**STOP is a real branch, not a formality.** The isolated-word training set is a
genuine risk. Discovering it in Week 1 with two viable fallbacks is the entire
purpose of spending the week this way.

### 7.6 Measured outcomes (record)

| # | Threshold | Measured | Status |
|---|---|---|---|
| G1 | ≤ 120 MB | *(re-measure on the exp2 export)* | blocking |
| **G2** | **≥ 0.85** | **0.8570** on RATL-Bench-v0, 1,374 words | **PASS**, margin +0.0070 |
| G3 | ≥ 0.80 | **0.9333** omission recall, fresh held-out tier | **PASS** |
| G4 | ≤ 0.05 | **0.0085** false-reject rate, 10.7% abstention, 89.3% coverage | **PASS** (§4.1) |
| G5 | ≤ 0.5 | desktop 0.0204 → device 0.061–0.102 (estimate) | blocking until measured on §7.3 |
| G6 | ≤ 400 MB | not measured | blocking |
| G7 | RTF ≤ 1.0 | not measured | non-blocking |

**G1–G6 remain the V1 blocking gates.** This subsection records measurement
status; it does not alter any threshold in §7.2.

**Scope rules for the G2 number (normative).** G2 is a word-boundary alignment
metric on a 150-clip, 5-reciter, 30-ayah benchmark. It is **not** an end-user
recitation-accuracy claim; G3 and G4 are the metrics closest to user experience
and are unmeasured. Any publication of 0.8570 must state metric definition,
±150 ms tolerance, corpus size and reciter count. **It must never be rounded or
restated as "86% accurate" or as an end-user accuracy figure.** Known weaknesses
to disclose: words > 4 s score 0.50; Mujawwad scores 0.7230.

Per §7.5 there is **no conditional PASS**. V1 is not declared viable until
G1–G6 are all measured and passing on the shipping code path.

---

## 8. Milestones

| # | Milestone | Target | Gate |
|---|---|---|---|
| M1 | Alignment quality frozen | Aug 25 | **G2 gate — done 2026-08-18, G2 = 0.8570** |
| M2.0 | ONNX export, measurement, conformance + G3/G4 groundwork | Aug 22 | G1 and RTF measured on the exp2 artifact; ONNX reproduces G2 |
| M2.1 | Kotlin/JVM RATL Core | Sep 1 | **`ratl-eval` reproduces G2 = 0.8570 from production code** |
| M2.2 | Taxonomy lattice + §5 API | Sep 10 | Batch ≡ streaming; G2 not regressed |
| M2.3 | G3 / G4 on the shipping path | Sep 16 | **G3 ≥ 0.80, G4 ≤ 0.05** |
| M3 | Android AAR + benchmark device | Sep 24 | **G5 and G6 measured on §7.3 hardware** |
| M4 | Emran integration, docs, contributor infrastructure | Oct 1 | Real application, real recitation |
| M5 | **Public release, RATL v1** | **Oct 3** | All §7.6 gates measured and passing |
| — | Itqan submission | Oct 3–5 | Release checklist complete |
| — | Buffer | Oct 4–10 | Hard deadline **Oct 10** |

**Cut order if slipping:** Android demo app → streaming → small tier.
**`ratl-eval` is never cut.** It is what separates RATL from a demo.

---

## 9. Open items

1. **Itqan coordination** — worth opening a conversation in Week 1–2 so RATL lands
   as the missing live-feedback layer alongside Munajjam, rather than as an
   outside project. Owner decision.
2. **Model distribution channel** — where the quantized model is hosted for the
   one-time download. Must remain free. Decide by Week 5.

---

## 10. Attribution

RATL builds on openly licensed work and must credit it:

- **Tarteel AI** — `everyayah` dataset (CC BY 4.0), QUL, `whisper-*-ar-quran`
  models (Apache-2.0)
- **QUL / Quranic Universal Library** — segmented recitations, mushaf layouts,
  scripts; `quran-align` word-level timings (CC BY 4.0)
- **Microsoft** — ONNX Runtime (MIT)
- **IQRA / Iqra'Eval organizers** — QuranMB, Iqra_train (CC BY 4.0)
- **Tanzil** — Quran text
- **quran.com** — API v4 word transliterations (**evaluation input only, not
  redistributed**; see the §5.5 text-source policy)
- **Collin Fair** — `quran-align` word-level timings (CC BY 4.0, verified)

Evaluated during development but **not shipped**, credited for the work their
availability made possible: **k2-fsa** (sherpa-onnx, Apache-2.0) and
**TBOGamer22** (`wav2vec2-quran-phonetics`, Apache-2.0).
