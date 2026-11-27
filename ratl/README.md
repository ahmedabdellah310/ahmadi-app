<div align="center">

# RATL

**An open-source, on-device Quran recitation assessment engine that developers can integrate into their applications.**

<p dir="rtl">محرّك مفتوح المصدر لتقييم تلاوة القرآن الكريم كلمةً كلمة، يعمل بالكامل على الجهاز: بلا خوادم، بلا مفاتيح API، بلا تتبّع.</p>

[![License: Apache-2.0](https://img.shields.io/badge/license-Apache--2.0-blue.svg)](LICENSE)
[![Release](https://img.shields.io/github/v/release/SammyNashed/ratl)](https://github.com/SammyNashed/ratl/releases/tag/v1.0.0)
![Platform](https://img.shields.io/badge/platform-Android%20%7C%20JVM-3ddc84)
![On-device](https://img.shields.io/badge/inference-100%25%20on--device-success)
[![Good first issues](https://img.shields.io/github/issues/SammyNashed/ratl/good%20first%20issue?label=good%20first%20issues&color=7057ff)](https://github.com/SammyNashed/ratl/issues?q=is%3Aissue+is%3Aopen+label%3A%22good+first+issue%22)

</div>

RATL takes a *known* target passage and a recording of someone reciting it, and returns a structured, per-word assessment — entirely on the device. No cloud inference, no API keys, no accounts, no telemetry.

RATL is an **SDK, not an app**. Its first consumer is [Emran](https://github.com/SammyNashed/Emran), an Android Quran app, where it powers blind recitation mode: recite from memory and see which words you missed.

### At a glance

- **Private by design** — audio never leaves the phone. The only network access RATL's design permits is a one-time model download.
- **Small and fast** — a 31 MB model, running at **0.135× real time** on a mid-range Android phone (MediaTek Dimensity 1080).
- **Honest** — every number in this README was measured, states its scope, and is reproducible from `ratl-eval`. When RATL is not sure, it says `UNCERTAIN` instead of accusing the reciter.
- **Open** — Apache-2.0, conformance vectors for every layer, and the full research record (including what failed) in `spike/`.

<details dir="rtl">
<summary><b>بالعربية</b></summary>

**RATL** محرّك (SDK) يدمجه المطوّرون في تطبيقاتهم، وليس تطبيقًا مستقلًا. يأخذ المقطع المستهدف من القرآن وتسجيلًا لتلاوة القارئ، ثم يعيد حكمًا على كل كلمة: صحيحة، أو محذوفة، أو مكرّرة، أو ناقصة، أو "غير متأكد" حين لا تكفي الثقة للحكم.

- كل المعالجة على الهاتف: لا يُرسَل الصوت إلى أي خادم، ولا يحتاج المستخدم إلى مفتاح API.
- نموذج بحجم 31 ميغابايت، أسرع من الزمن الحقيقي بنحو سبع مرات على هاتف أندرويد متوسط.
- لا يقيّم أحكام التجويد، ولا يتّهم القارئ بإبدال كلمة ما لم يكن متأكدًا. الدقة هنا أهم من كثرة الأحكام.
- مستخدم فعليًا في تطبيق [عِمران](https://github.com/SammyNashed/Emran) لميزة التسميع الغيبي.

**تريد المساهمة؟** ابدأ من [المهام المفتوحة](https://github.com/SammyNashed/ratl/issues)، وفيها مهام مناسبة للمبتدئين.

</details>

---

## Quick start

**1. Get the model** (attached to the [v1.0.0 release](https://github.com/SammyNashed/ratl/releases/tag/v1.0.0), not stored in git):

```bash
mkdir -p models/out && cd models/out
curl -LO https://github.com/SammyNashed/ratl/releases/download/v1.0.0/ratl-align-v1-e2e-fp32.onnx
curl -LO https://github.com/SammyNashed/ratl/releases/download/v1.0.0/vocab.json
sha256sum ratl-align-v1-e2e-fp32.onnx
# 95a01016aa1383bf60d28edfe5eaf6baecb65b71280cc5085b67ce05545d2798
```

**2. Build and run the conformance suite.** You need JDK 17+ and the Android SDK (set `ANDROID_HOME`, or `sdk.dir` in `local.properties`), because the build includes the `ratl-android` module. The Gradle wrapper downloads Gradle itself.

```bash
./gradlew check
```

**3. Integrate it** — see [Using RATL on Android](#using-ratl-on-android) below, and [`docs/EMRAN_INTEGRATION.md`](docs/EMRAN_INTEGRATION.md) for a real app's integration, end to end.

---

## What RATL does

Given a target passage and audio, RATL returns one status per target word:

| Status | Meaning |
|---|---|
| `CORRECT` | Recited, aligned, above the confidence threshold |
| `OMISSION` | Target word absent from the recitation |
| `REPETITION` | Word recited more than once consecutively |
| `INSERTION` | Extra content between target words (a session event) |
| `INCOMPLETE` | Word begun then truncated — audio ended, or the reciter broke off |
| `UNCERTAIN` | **Abstention.** RATL declines to judge |
| `PENDING` | Streaming only: the audio has not reached this word yet |
| `SUBSTITUTION` | In the taxonomy, **but V1 never emits it** — see *Limitations* |

Plus per-word timings, a confidence score, and the model hash that produced the result.

---

## Limitations — please read before integrating

RATL is deliberately narrow. Two limitations are important enough that the specification requires stating them here.

### Tajweed is not assessed

RATL does **not** diagnose tajweed (madd, ghunnah, qalqalah, ikhfa, …) or phoneme-level mispronunciation, and it will not tell you whether a recitation was *beautiful* or *correct in tajweed*.

This is not an oversight. Phoneme-level mispronunciation detection is an open research problem: the IQRA 2026 organiser baseline scores **F1 = 0.4414**, roughly a coin flip. Shipping a tajweed verdict at that quality in a religious application is worse than shipping nothing.

### V1 never asserts SUBSTITUTION

If a reciter says a *different word* in a target word's position, V1 reports **`UNCERTAIN`**, not `SUBSTITUTION`.

Measured: across 10,560 threshold configurations on the selection tier, 768 met the false-reject budget, and **none of them retained substitution detection**. The cheapest configuration that did detect substitutions pushed the held-out false-reject rate to roughly 0.08, against a 0.05 ceiling. Rather than accuse reciters wrongly, RATL declines to judge.

`SUBSTITUTION` remains in the public enum — a later version may emit it, and **consumers must handle it defensively**.

### Other boundaries

- Accuracy degrades with word duration. Words longer than 4 s are the weakest case.
- Slow *mujawwad* recitation is measurably harder than *murattal*.
- Out-of-order recitation is surfaced as a session event, not a word status.
- Pauses, breaths and hesitation are never errors.

---

## Measured results

Every gate below was measured, and every number states its scope. **Nothing here is an estimate.**

| Gate | Threshold | Measured | |
|---|---|---|---|
| G1 model size | ≤ 120 MB | **30.99 MB** | ✅ |
| G2 word-boundary accuracy | ≥ 0.85 | **0.8570** | ✅ |
| G3 omission recall | ≥ 0.80 | **0.9333** | ✅ |
| G4 false-reject rate | ≤ 0.05 | **0.0085** | ✅ |
| G5 real-time factor | ≤ 0.5 | **0.13517** | ✅ |
| G6 peak RSS | ≤ 400 MB | **350.7 MB** | ✅ |

### What G2 = 0.8570 actually means

**It is not an end-user accuracy figure and must not be quoted as one.**

It is the fraction of word boundaries whose predicted start *and* end fall within **±150 ms** of expert-validated ground truth, computed over **all** words of **RATL-Bench-v0**: 150 clips, 1,374 words, 5 reciters, 30 ayat. Start accuracy 0.8872, end accuracy 0.8268. Words over 4 s score 0.50; mujawwad scores 0.7230.

G3 and G4 are the numbers closest to what a user experiences.

### Where G5 and G6 were measured

Xiaomi **Redmi Note 12 Pro+ 5G** (`22101316UG`), MediaTek **MT6877V/TTZA**, **Android 14** (SDK 34), build `UP1A.231005.007`, arm64-v8a, single-threaded, thermal status `NONE` throughout. RTF includes feature extraction. Worst single clip 0.28682 — short clips cost proportionally more because a 2 s clip still pays for a full 15 s window.

---

## Using RATL on Android

```kotlin
val engine = RatlAndroid.engineFromDir(modelDir)   // graph + vocab.json

// Batch
val assessment = engine.assessWords(pcm16kMono, targetWords)
assessment.words.forEach { w ->
    println("${w.qulKey}: ${w.status} (${w.tStartMs}–${w.tEndMs} ms)")
}

// Streaming
val session = engine.createSession(targetWords)
session.pushAudio(chunk)        // PartialAssessment; provisional, may be revised
val finalResult = session.finish()
```

**Audio must be 16 kHz mono float PCM.** Decoding and resampling are the caller's responsibility — RATL takes samples, not files.

**Partial results are provisional.** `pushAudio` may revise words it has already reported, because more audio changes the alignment of what came before. Do not render a partial verdict as final.

### You supply the Quran text; RATL romanises it

RATL ships the tokenizer, the model vocabulary and an Uthmani→romanisation
`Normalizer`. It does **not** bundle Quran text, translations or
transliterations — your app already has its own text, and this keeps RATL free
of third-party text licensing.

```kotlin
val words = Normalizer.romanizeVerse(uthmaniAyahText)
engine.assessWords(pcm, words)
```

**The Normalizer is a rule-based approximation, and that costs accuracy.**
Measured against 4,287 reference ayat (33,932 words): exact word agreement
0.6354, character error rate 0.0788. Some of the residual is not derivable by
rule — the reference itself writes `mus'taqima` but `nasta'inu` for the same
pattern.

What matters is the effect on alignment, which was measured end to end:

| Target source | G2 |
|---|---|
| reference transliterations | 0.8570 |
| **Arabic via `Normalizer`** | **0.8504** |

That clears the 0.85 gate **by 0.0004 — one boundary in 2,748.** It is a pass,
but it is not a comfortable one, and improving the Normalizer is the single
highest-value contribution available ([issue #10](https://github.com/SammyNashed/ratl/issues/10)). If you already
hold romanised text matching the training scheme, pass it directly and you get
the 0.8570 path instead.

---

## Repository layout

```
ratl-core/           alignment lattice, taxonomy, scoring — pure Kotlin/JVM, no android.*
ratl-text/           normalization, tokenization, vocabulary
ratl-acoustic-ort/   ONNX Runtime AcousticModel
ratl-eval/           evaluation harness + CLI — released standalone
ratl-android/        Android AAR
conformance/         golden vectors (T0–T3)
models/              export + quantization scripts (no weights in git)
eval/                mutation corpus generator, reference lattice
spike/               the research record — preserved, never refactored
```

`ratl-core` has **zero** Android dependencies and is verified so by the build, which fails on any `android.*` import. The same core runs on desktop JVM and Android.

Rust + UniFFI bindings are planned for **v1.1**; V1 is Kotlin/JVM.

---

## Reproducing the results

```bash
# G2 from the production code path
./gradlew :ratl-eval:run

# conformance vectors
./gradlew check

# G5/G6 on a connected device
./deploy_m3_device.sh
```

`ratl-eval` is a released component, not a dev script. It is what separates RATL from a demo.

---

## Licence and attribution

RATL is **Apache-2.0**. See [`LICENSE`](LICENSE).

RATL builds on openly licensed work:

- **Tarteel AI** — `whisper-tiny-ar-quran` (Apache-2.0), the encoder RATL fine-tunes; `everyayah`; QUL
- **QUL / Quranic Universal Library** — segmented recitations and tooling
- **Collin Fair** — `quran-align` word-level timings (**CC BY 4.0**)
- **Microsoft** — ONNX Runtime (MIT)
- **quran.com** — API v4 word transliterations, used as an *evaluation input only* and never redistributed
- **Tanzil** — Quran text
- **IQRA / Iqra'Eval organisers** — QuranMB, Iqra_train (CC BY 4.0)

Evaluated during development but not shipped: **k2-fsa** (sherpa-onnx, Apache-2.0) and **TBOGamer22** (`wav2vec2-quran-phonetics`, Apache-2.0).

---

## Contributing

RATL is part of Itqan's **Code Serves the Quran** campaign, and contributions are very welcome. Every open issue names a real, measured gap — nothing is invented to look welcoming.

| Start here | Issue |
|---|---|
| 🟢 Good first issue | [#10 Improve the Arabic Normalizer](https://github.com/SammyNashed/ratl/issues/10) · [#4 Broaden the evaluation corpus](https://github.com/SammyNashed/ratl/issues/4) · [#7 Integration examples](https://github.com/SammyNashed/ratl/issues/7) |
| 🛠️ Engineering | [#1 Port the core to Rust](https://github.com/SammyNashed/ratl/issues/1) · [#5 Python binding](https://github.com/SammyNashed/ratl/issues/5) · [#6 JS / WASM binding](https://github.com/SammyNashed/ratl/issues/6) · [#8 Reduce peak memory](https://github.com/SammyNashed/ratl/issues/8) |
| 🔬 Research | [#2 Long words (> 4 s)](https://github.com/SammyNashed/ratl/issues/2) · [#3 Substitution detection](https://github.com/SammyNashed/ratl/issues/3) · [#9 int8 model](https://github.com/SammyNashed/ratl/issues/9) |

See [`CONTRIBUTING.md`](CONTRIBUTING.md) before opening a pull request. The research record in `spike/` explains why the design is what it is, including the approaches that failed — those negative results are load-bearing and are preserved deliberately.
