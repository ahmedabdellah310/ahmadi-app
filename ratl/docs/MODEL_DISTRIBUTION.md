# Model distribution

RATL's weights are not in git. This document is the answer to SPEC §9 open item 2 — where the model lives, and how an application gets it.

## The artifact

| | |
|---|---|
| File | `ratl-align-v1-e2e-fp32.onnx` |
| Size | **30.99 MB** (G1 ceiling 120 MB) |
| SHA-256 | `95a01016aa1383bf60d28edfe5eaf6baecb65b71280cc5085b67ce05545d2798` |
| Sidecar | `vocab.json` — 35 tokens, must accompany the graph |
| Licence | Apache-2.0 |

The graph takes reflect-padded 16 kHz PCM and returns CTC logits. The log-mel front end is **inside** the graph, so integrators port no DSP and there is no filterbank to drift out of sync with the weights.

### Why fp32 and not int8

An int8 build exists at 10.75 MB and is **not** what V1 ships. It does not reproduce the frozen result: it diverges on 3.78% of word boundaries and lands at G2 0.8588 rather than 0.8570. fp32 uses 26% of the size budget and reproduces the frozen pipeline exactly, so the 20 MB is worth it. int8 is a candidate for v1.1 once it can be gated on its own merits.

## Channel: GitHub Releases

The model is attached to the tagged GitHub release.

```
https://github.com/SammyNashed/ratl/releases/download/v1.0.0/ratl-align-v1-e2e-fp32.onnx
https://github.com/SammyNashed/ratl/releases/download/v1.0.0/vocab.json
```

Chosen because it is free with no quota that a small project will reach, needs no account to download, is versioned with the tag it belongs to, and adds no new organisation to trust beyond the one already hosting the source. Hugging Face is a reasonable mirror if bandwidth ever becomes a problem; nothing in RATL depends on which host serves the bytes.

## Verify before you trust it

`Assessment.model_id` is the SHA-256 of the graph that produced the result, so a claim is reproducible only if the hash is known. **Always verify after download** — a truncated file will otherwise fail in confusing ways much later.

```kotlin
require(RatlAndroid.sha256(modelFile) == EXPECTED_SHA256) {
    "model hash mismatch - refusing to load"
}
```

```bash
sha256sum ratl-align-v1-e2e-fp32.onnx
```

## How applications should ship it

Two supported patterns. Pick on whether your users are bandwidth-constrained.

**Bundle it in the APK** (simplest, +31 MB install). Put the graph and `vocab.json` in `assets/`, extract once on first run, verify the hash. Works offline from the first launch, which matters for a Qur'an app. Mark the graph `noCompress` so install and first load stay fast:

```kotlin
androidResources { noCompress += listOf("onnx") }
```

**Download on first use** (smaller install, needs one-time connectivity). Fetch to app storage, verify the hash, then cache permanently. This is the *only* network access RATL's design permits (SPEC §1); after it, RATL is fully offline forever.

Do **not** re-download on every launch, and do not stream weights at runtime. RATL is an on-device engine; treating the model as a remote resource defeats the point.

## Reproducing the artifact

The export is scripted and deterministic from the frozen checkpoint:

```bash
python models/export_exp2.py        # graph + vocab.json, measures G1 and RTF
python models/export_e2e.py         # folds the mel front end into the graph
python models/verify_onnx_g2_e2e.py # must reproduce G2 = 0.8570
```

If a rebuild produces a different hash, that is expected — ONNX serialisation is not byte-stable across versions. What must not change is the **measured behaviour**: `verify_onnx_g2_e2e.py` must still report G2 = 0.8570 exactly, and the conformance vectors must still pass. Republishing a model means republishing its hash and re-running both.
