# RATL Week 1 — Day 1 Notes

**Date:** 2026-08-13
**Scope:** research, environment setup, model inspection, dataset inspection.
No RATL Core, no SDK, no production code. Per `WEEK1_PLAN.md` §2.

---

## 1. Benchmark device (SPEC §7.3)

**Xiaomi Redmi Note 12 Pro+ 5G — Android 14**

| Property | Value | Source |
|---|---|---|
| SoC | MediaTek Dimensity 1080, 6 nm | published specs |
| CPU | 2× Cortex-A78 @ 2.6 GHz + 6× Cortex-A55 @ 2.0 GHz | published specs |
| GPU | Mali-G68 MC4 | published specs |
| RAM | LPDDR4X — variant **TBC on device** (6/8/12 GB sold) | — |
| Storage | UFS 2.2 — variant **TBC on device** (128/256 GB sold) | — |
| Android | 14 — build number **TBC on device** | owner |

**Action for Day 5:** capture exact variant and build via
`adb shell getprop` (`ro.product.model`, `ro.build.version.release`,
`ro.build.display.id`, `MemTotal`). Do not report published marketing specs as
measured facts.

**Relevance to G5:** the big cores are Cortex-A78 at 2.6 GHz. This is a genuinely
mid-range 2022 SoC — a fair and unflattering benchmark target, which is what we
want. sherpa-onnx's published reference points (14M Zipformer on Cortex-A7;
Pixel 6 at ~2% battery/hour) suggest headroom, but those are far smaller models
than 94.4M.

---

## 2. Primary model inspection

`TBOGamer22/wav2vec2-quran-phonetics`

| File | Size |
|---|---|
| `model.safetensors` | **378 MB** |
| `config.json` | 2.43 kB |
| `vocab.json` | 473 B |
| `tokenizer_config.json` | 1.18 kB |
| `preprocessor_config.json` | 266 B |

378 MB is consistent with 94.4M parameters at fp32, as the model card claims.
Naive int8 quantization projects to **~95 MB**, inside the 120 MB G1 ceiling —
but this is arithmetic, not a measurement. **G1 is measured on Day 2.**

**Preprocessor config (confirmed):**
```json
{ "do_normalize": true, "feature_extractor_type": "Wav2Vec2FeatureExtractor",
  "feature_size": 1, "padding_side": "right", "padding_value": 0.0,
  "processor_class": "Wav2Vec2Processor", "return_attention_mask": true,
  "sampling_rate": 16000 }
```
Raw-waveform input, 16 kHz, zero-mean/unit-variance normalization,
attention mask required. Standard wav2vec2. No surprises.

---

## 3. ⚠️ Principal Day 1 finding — the vocabulary is romanized Latin, not Arabic

The full vocabulary is **38 tokens**:

```
space  '  -  H  a b d e f g h i j k l m n o q r s t u w y z
ā ī ū   ʿ   ḍ ḥ ṣ ṭ ẓ   |   [UNK]=36  [PAD]=37
```

This is **not** Arabic script, and **not** IPA, and **not** the IQRA 68-phoneme
MSA inventory. It is a **modified ISO/ALA-LC romanized transliteration** of
Quranic Arabic.

Observed properties:
- Long vowels via macrons: `ā ī ū`
- Emphatics as underdotted Latin: `ḍ ḥ ṣ ṭ ẓ`
- `ʿ` for ʿayn, `'` for hamza/glottal stop
- **Digraphs** for ث ذ ش غ خ → `th dh sh gh kh` (no dedicated single tokens exist)
- `-` used in article/prefix joins (`al-raḥmāni`, `l-ṣirāṭa`)
- `|` as the word delimiter, ` ` as a separate token
- No digits, no shadda/sukun symbols — gemination presumably by doubling

### Why this matters

`SPEC.md` §5.5 requires `Normalizer.tokenize(uthmani) -> [TargetWord]` to produce
**model-vocabulary tokens**. That means RATL must convert Uthmani Arabic into
*this specific romanization*, matching the training convention **exactly**. A
convention mismatch — one hyphen policy, one hamzat-wasl rule — produces
systematic forced-alignment failure that would look like model failure.

This is the vocabulary-mapping friction predicted for Day 3 in `WEEK1_PLAN.md`
§5, and it is **larger than anticipated**: it is a transliteration problem, not a
token-id remapping problem.

---

## 4. ✅ The mitigation — it is a lookup, not a transducer

`Buraaq/quran-md-words` — the model's own training set — contains:

| Field | Meaning |
|---|---|
| `surah_id`, `ayah_id`, `word_id`, `word_index` | positional key |
| `word_ar` | Arabic script |
| **`word_tr`** | **transliteration in the training convention** |
| `audio` | word-level MP3 |

**77,429 word-level recordings covering all 114 surahs (~20 h, ~2.05 GB).**

The Quran contains roughly 77,430 words by common counts, so this is
**effectively complete coverage**. The positional key is the same
`surah:ayah:word` structure RATL already uses for `qul_key`.

**Consequence:** the Uthmani → model-token mapping can be a **precomputed lookup
table joined on (surah, ayah, word_index)**, not a hand-written rule-based Arabic
transliterator that must guess an undocumented convention.

This converts the single largest Week 1 risk into a data join. It is the most
important result of Day 1.

**Residual work:** a transducer is still eventually wanted for robustness
(unseen orthography, partial words, `INCOMPLETE` handling), but it is **not on
the Week 1 or V1 critical path**, and it can be *validated against* 77k ground
truth pairs rather than written blind.

---

## 5. ⚠️ Open risk — `Buraaq/quran-md-words` license is unstated

No LICENSE file and no license metadata visible on the dataset repository. Under
SPEC §1, only Apache-2.0 / MIT / CC BY 4.0 artifacts may be used.

**Status:** unresolved. Does **not** block Day 2–3 (local research use), but
**does** block redistributing a derived lookup table in the RATL SDK.

**Mitigations, in order:**
1. Read the referenced paper (arXiv 2601.17880) and the dataset README for terms;
   contact the author if needed.
2. **Source the transliteration from QUL instead** — QUL publishes 10
   transliteration resources under known terms. Requires verifying QUL's
   convention matches the model's training convention. *This is a concrete
   Day 2/3 check and the preferred path if it holds.*
3. Ship RATL without a bundled table and generate it at build time from a
   user-supplied source.

**Escalation trigger:** if neither 1 nor 2 resolves by end of Day 3.

---

## 6. Ground truth for G2 — confirmed available

QUL publishes **59 recitations with segment data**, exportable as **JSON and
SQLite**. Word-level segments are available for several reciters (Ahmad
Alnufais, Hady Toure, Mishari Rashid al-`Afasy Streaming, Abdul Basit Abdul
Samad, Yasser ad-Dussary); one (Abdul Basit — Mujawwad) additionally offers
letter-level segments.

This satisfies `SPEC.md` §3.1: G2 has a real, expert-validated word-boundary
ground truth. **Terms of use at tarteel.ai must be reviewed before download.**

**Note on reciter diversity:** the word-level-segment reciters skew toward
murattal. `RATL-Bench-v0` requires ≥2 recitation speeds; speed stratification
(SPEC §7.5, PIVOT-acoustic branch) depends on getting genuinely fast recitation
into the probe set. **Flagged as a Day 2 corpus-selection constraint.**

---

## 7. Environment

| Item | Finding | Decision |
|---|---|---|
| Python 3.14.5 (default `python`) | newest CPython; torch wheels lag it | **Rejected** |
| Python 3.13.3 (`py -3.13`) | stable, full torch/transformers support | **Selected** |
| git | present | — |
| adb | present; **no device attached** | expected — Day 5 |
| cargo / Rust | **absent** | not needed until Week 3 |
| Free disk | **53.7 GB** | sufficient for spike |

**Disk consequence:** 53.7 GB free confirms `everyayah` (~117 GB) **must be
streamed/selected, never fully downloaded** — as `WEEK1_PLAN.md` §5 already
requires. `quran-md-words` at ~2.05 GB is small enough to pull locally if needed.

Spike venv: `ratl/spike/.venv` (Python 3.13, CPU-only torch).
Dependencies in `ratl/spike/requirements.txt`.

---

## 8. Day 1 status

| Task | Status |
|---|---|
| Spike environment created | ✅ (install running) |
| Primary model verified, files + configs inspected | ✅ |
| Model vocabulary decoded | ✅ — **principal finding, §3** |
| Training-set convention identified | ✅ — **mitigation, §4** |
| QUL segment ground truth confirmed | ✅ |
| Benchmark device recorded | ⚠️ partial — variant TBC on device |
| Model license verified on repo (Apache-2.0) | ✅ |
| Dataset license verified | ❌ **open risk, §5** |
| `RATL-Bench-v0` assembled | ⏭ Day 2 (moved) |
| Ground-truth spot-check (owner) | ⏭ pending owner |

**Schedule note:** `RATL-Bench-v0` assembly moves from Day 1 to Day 2. Day 1 was
consumed by the vocabulary investigation (§3), which was the correct trade — it
surfaced the real Day 3 risk a full two days early and found its mitigation in
the same pass.

**No gate outcomes yet.** G1 is measured on Day 2. Nothing in Day 1 changes the
`SPEC.md` §7.5 decision rule.
