# Contributing to RATL

Thank you for considering it. RATL is a Quran recitation assessment engine, and the standards below exist because getting this wrong means telling someone they recited the Qur'an incorrectly when they did not.

---

## The one rule that matters most

**Measure, don't assume — and report what you measured, including when it disproves your idea.**

The `spike/` directory preserves every approach that failed, with its numbers. Data scaling made accuracy *worse*. Six boundary rules were tried before one worked. Substitution detection was attempted twice and abandoned on evidence. Those negative results are load-bearing: they are why the current design is what it is, and they save the next person from repeating the work.

If you propose a change, bring the measurement. If the measurement disagrees with you, say so — that is a contribution too.

---

## Getting set up

You need a **JDK 17+** and, for Android work, **Android Studio**. That's all.

```bash
git clone <repo>
cd ratl
./gradlew check          # unit tests + conformance vectors
```

There is **no Rust toolchain, no NDK and no CMake** in V1. If a change appears to need one, that is a design discussion, not a build fix.

To run the evaluation harness you need the model artifact, which is not in git (see *Model distribution* in the README).

```bash
./gradlew :ratl-eval:run     # reproduces G2 on RATL-Bench-v0
```

---

## Things that must not change casually

These are frozen. Changing any of them invalidates published results, so each needs an explicit decision and fresh measurements — not a pull request that quietly moves a number.

| Frozen | Where |
|---|---|
| The acoustic model, its vocabulary, and the exported graph | SPEC §2.1 |
| The `M2E_edge_spike` boundary rule | SPEC §7.6 |
| 15 s window / 10 s hop / 2.5 s margin discard, 50 fps, 0 ms offset | SPEC §2.1 |
| Gate thresholds G1–G7 | SPEC §7.2 |
| `RATL-Bench-v0` — the gate corpus | SPEC §3.2 |

**`RATL-Bench-v0` is a gate, not a tuning signal.** Select parameters on the held-out selection tier; run the bench once per selected configuration. Tuning against the bench makes the number meaningless and will be rejected.

### The lattice invariant

The error lattice extends the frozen forced aligner with skip / garbage / repeat / truncation arcs. **With every arc disabled it must remain bit-identical to the frozen aligner.** That is what guarantees G2 is unaffected by taxonomy work, and it is enforced by `LatticeInvariantTest` and `eval/conformance_lattice.py`. If your change breaks that test, the change is wrong — not the test.

---

## Conformance vectors

`conformance/vectors/` holds golden vectors in four tiers: pure functions (T0), the audio front end (T1), decode to frame boundaries (T2), and full `Assessment` output (T3). They are generated from the reference implementation and are the executable specification.

**Assert exactly on discrete outputs; tolerate only continuous ones.** Frame indices come from an argmax path — a tolerance there hides a real divergence rather than absorbing numerical noise. If you must widen a tolerance, measure the actual spread first and record the measurement in the code, as the existing ones do.

Regenerating vectors is a deliberate act. A pull request that regenerates them to make a test pass will be rejected; one that regenerates them because behaviour intentionally changed, with the reasoning stated, is welcome.

---

## Style

Match the surrounding code. Beyond that:

- Comments explain **why**, especially where something looks odd. If a branch exists because removing it destroyed 21% of boundaries, say so — that comment is worth more than the code.
- Public API changes must match SPEC §5. Adding a `WordStatus` value is a **breaking change**.
- No new runtime dependencies in `ratl-core` or `ratl-text`. They are deliberately dependency-free so they stay portable.
- No `android.*` imports outside `ratl-android`. The build enforces this.

---

## Pull requests

1. One concern per PR.
2. `./gradlew check` passes.
3. If you touched anything on the decode path, re-run `:ratl-eval:run` and state the G2 you got.
4. Say what you measured, on what, and what you did not measure.

Uncertainty is welcome. "I think this helps but I could only test it on two reciters" is a useful PR description. Overclaiming is not.

---

## Good first contributions

See the issue tracker for `good first issue`. Broadly, the areas most open to help:

- **Language bindings** — Python, JS/WASM, Swift, Dart. The conformance vectors make these safe to attempt.
- **The Rust core** (v1.1) — `ratl-core` is a bounded body of numeric code with an executable specification.
- **Long-word accuracy** — words over 4 s are the weakest measured case.
- **Corpus breadth** — more reciters and speeds, reciter-disjoint.
- **Documentation and integration examples.**

---

## Reporting a problem

Useful reports include: the target text, roughly what was recited, the statuses RATL returned, the `model_id` from `Assessment`, and the device. Audio helps if you can share it — but **never share a recording of someone without their permission**.

If RATL flagged an error that was not an error, that is a **false reject**, the most serious failure mode in this project. Say so explicitly and it will be prioritised.

---

## Conduct

Be courteous and assume good faith. This project touches something people hold sacred; disagreement about engineering is expected, disrespect is not.
