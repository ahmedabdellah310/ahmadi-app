# Contributor issues to file at release

Ready-to-post issue bodies. Each names a real, measured gap — nothing invented to look welcoming. Labels in brackets.

---

## 1. Port RATL Core to Rust for v1.1 `[enhancement] [help wanted] [v1.1]`

V1 ships a Kotlin/JVM core (SPEC §6.1). Rust + UniFFI was deferred deliberately, with the cost stated openly: a JVM core makes the Python and JS/WASM bindings materially harder, and deferred ports have a habit of never happening.

This issue is the counterweight.

`ratl-core` is a bounded body of numeric code — feature windowing, a Viterbi lattice, a boundary rule, a taxonomy — with **no Android or platform dependencies**. It has an executable specification: the conformance vectors in `conformance/vectors/` (T0–T3) pin every function from pure arithmetic up to a full `Assessment`.

**Definition of done:** a Rust `ratl-core` passing all four conformance tiers, and `ratl-eval` reproducing **G2 = 0.8570** on RATL-Bench-v0 from the Rust path.

**Start here:** `ratl-core/src/main/kotlin/ai/ratl/core/ForcedAligner.kt` and `ErrorLattice.kt`. Read the comments about tie-breaking before you begin — they are correctness, not style.

---

## 2. Improve accuracy on long words (> 4 s) `[research] [help wanted]`

Boundary accuracy degrades monotonically with word duration, measured across every reciter:

| Ground-truth duration | n | accuracy |
|---|---|---|
| 0–500 ms | 109 | 0.9495 |
| 500–1000 ms | 427 | 0.8923 |
| 1000–2000 ms | 556 | 0.8399 |
| 2000–4000 ms | 229 | 0.7686 |
| **> 4000 ms** | 53 | **0.4906** |

The ±150 ms tolerance is fixed, so a 4 s madd demands 3.75% relative precision while a 500 ms word needs 30%. This is also why slow *mujawwad* scores 0.7230 — it is the style with the longest words, not a distinct failure mode.

**Known unexploited lead:** during Exp 2, decode variant `V_win_25` (boundary-posterior DP constrained to ±0.5 s) scored **0.8264 on Mujawwad** versus 0.7934 for the shipped decoder, while losing overall. A duration-conditional hybrid was declined at the time because it added a selection degree of freedom for ~0.01 overall. With a larger corpus it may be worth revisiting.

**Read first:** `spike/M1B_RESULTS.md`, `spike/EXP2_RESULTS.md`. Two repair attempts already failed — please don't repeat them.

---

## 3. Substitution detection that survives a 0.05 false-reject budget `[research] [help wanted]`

V1 never emits `SUBSTITUTION`; suspected substitutions surface as `UNCERTAIN` (SPEC §4.1).

Measured: of 10,560 threshold configurations on the selection tier, 768 met the false-reject budget and coverage floor, and **none retained substitution detection**. The cheapest substitution-active configuration cost a held-out FRR near 0.08 against a 0.05 ceiling.

The signal that failed was a per-word acoustic gap, `mean(max logp) − mean(logp at path label)`. Reciter-normalising it (leave-one-out robust z-score within the clip) lifted held-out precision from 0.0368 to 0.2093 — a real improvement, still nowhere near enough.

**What would move this:** a signal that distinguishes *"a different word was said here"* from *"this reciter's voice fits the model poorly"*. The second currently dominates.

**Definition of done:** substitution precision high enough that enabling it keeps held-out G4 ≤ 0.05, demonstrated on a reciter-disjoint tier that was not used for selection.

---

## 4. Broaden the evaluation corpus `[good first issue] [help wanted]`

RATL-Bench-v0 is 150 clips, 5 reciters, 30 ayat, 1,374 words. That is a Week-1 feasibility probe doing duty as a release gate, and it is the single biggest caveat on every published number.

The held-out G3/G4 tier is 400 non-bench ayat from **two** reciters — the only two the frozen model never trained on.

**Useful contributions:** more reciters with word-level timings (CC BY 4.0 or compatible); more speed and style variety; a larger reciter-disjoint held-out set. Everything is generated from manifests, so a contribution is a manifest plus a licence, not a pile of audio.

**Read first:** `spike/corpus/ratl-bench-v0.json`, `eval/corpus/mutate.py`. Reciter-disjoint splits are mandatory (SPEC §3.2).

---

## 5. Python binding `[enhancement] [help wanted] [v1.1]`

The evaluation tooling is Python but there is no Python *binding* — no way to call RATL from Python without the JVM.

Cleanest path is via the Rust core (issue #1) with PyO3. A JPype/JNI shim against the JVM core is a legitimate interim step if someone wants it sooner.

**Definition of done:** `pip install ratl`, an `Engine.assess` that matches the T3 conformance vectors.

---

## 6. JS / WASM binding `[enhancement] [help wanted] [v1.1]`

G7 (WASM loads and runs, RTF ≤ 1.0) is specified as **non-blocking** and has never been measured. ONNX Runtime Web exists, so the acoustic half is tractable; the alignment core is the part that needs porting, and depends on issue #1.

**Definition of done:** RATL running in a browser tab, T0–T3 vectors passing, and the first real G7 measurement.

---

## 7. Integration examples `[good first issue] [documentation]`

The README shows the API. There is no worked example of the pieces an integrator actually has to solve: capturing from `AudioRecord` at 16 kHz mono, resampling, chunking into a streaming session, and rendering provisional results without showing a partial verdict as final.

**Definition of done:** a small, readable sample app — proving the SDK, not a product.

---

## 8. Reduce peak memory `[enhancement] [help wanted]`

Peak RSS on the benchmark device is **350.7 MB** against a 400 MB ceiling. That is 12% headroom, the tightest gate in the project, and an integrating app's own footprint eats into the same budget.

**Leads, none investigated:** ONNX Runtime arena configuration; `Windowing.runWindows` accumulating all logits before decoding rather than streaming them; the JVM heap holding `Array<DoubleArray>` for log-probabilities where a flat primitive buffer would do.

**Definition of done:** a measured reduction on the SPEC §7.3 device with G2 unchanged at 0.8570.

---

## 9. int8 model that reproduces the frozen result `[research] [v1.1]`

An int8 export exists at 10.75 MB versus 30.99 MB for fp32, but V1 ships fp32 because int8 **does not reproduce frozen behaviour**: it diverges on 3.78% of word boundaries and lands at G2 0.8588 rather than 0.8570.

The difference is small and not obviously harmful — it simply is not the frozen pipeline, and shipping it would invalidate the conformance vectors.

**Definition of done:** either a quantisation that reproduces G2 = 0.8570 exactly, or a gated argument that the int8 variant should be offered as a separate, separately-measured artifact.

**Read first:** `models/verify_onnx_g2.json`.

---

## 10. Improve the Arabic Normalizer (Uthmani → romanisation) `[good first issue] [help wanted]`

Integrators pass Uthmani Arabic through `Normalizer.romanizeVerse`, and that path is measurably weaker than feeding reference transliterations:

| Target source | G2 |
|---|---|
| reference transliterations | 0.8570 |
| Arabic via `Normalizer` | **0.8504** |

0.8504 clears the 0.85 gate by **one boundary in 2,748**. Measured against 4,287 reference ayat (33,932 words), the Normalizer's exact word agreement is 0.6354 and its character error rate 0.0788.

This is the single highest-value contribution available: every app that integrates RATL from Arabic text goes through this code. It is rule-based Kotlin with no ML, so it is approachable without a speech background.

**Definition of done:** a higher exact-word agreement against the reference set, and an end-to-end G2 from the Arabic path that moves toward 0.8570, with the T0 normalizer conformance vectors updated deliberately, not incidentally.

**Start here:** `ratl-text/src/main/kotlin/ai/ratl/text/Normalizer.kt`. Some residual is not derivable by rule (the reference writes `mus'taqima` but `nasta'inu` for the same pattern), so aim for the derivable cases first.
