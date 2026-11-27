# Emran integration — measured record

Emran is RATL's first integrator. This records what integrating actually cost,
measured rather than estimated.

## Status

| Step | Status |
|---|---|
| RATL wired into Emran (composite build) | done |
| Real usage site (`RecitationAssessor`) | done |
| Model bundled in app assets, hash-verified on load | done |
| Emran builds with RATL | done |
| APK / AAB size impact | **measured** |
| Integrated RAM on the device | **blocked** — see below |

Integration was performed against the `emran.zip` snapshot dated 2026-08-14,
extracted to `C:\Users\sammy\emran-ratl`. The original working directory
(`C:\Android-Projects\Emran`) no longer exists. If a newer copy exists, these
changes must be re-applied there; they are small and additive.

## What changed in Emran

Three edits and one new file:

1. `settings.gradle.kts` — `includeBuild("C:/Users/sammy/ratl")`
2. `app/build.gradle.kts` — `implementation("ai.ratl:ratl-android:1.0.0-SNAPSHOT")`,
   ABI splits, and `noCompress` for the `.onnx` graph
3. `app/src/main/assets/ratl/` — the model and `vocab.json`
4. `app/src/main/java/com/example/recitation/RecitationAssessor.kt` — the
   integration itself

RATL's `minSdk` was lowered 26 → 24 to match Emran rather than force Emran up.
ONNX Runtime supports 21+, so this costs nothing.

No RATL model, decoder, core logic or gate result was changed.

## Size impact — measured

Debug builds, same configuration before and after.

| Artifact | Bytes | MB |
|---|---|---|
| Emran baseline APK (no RATL) | 24,646,759 | 23.5 |
| Emran + RATL, **arm64-v8a** | 75,710,047 | 72.2 |
| Emran + RATL, armeabi-v7a | 70,296,653 | 67.0 |
| Emran + RATL, universal (all ABIs) | 131,098,850 | 125.0 |
| Emran + RATL, **AAB** | 79,200,004 | 75.5 |

**Incremental cost of RATL on arm64-v8a: 51,063,288 bytes (48.7 MB).**

That splits almost exactly into:

- **31.0 MB** — the ONNX graph, stored uncompressed so first load is fast
- **17.7 MB** — ONNX Runtime native libraries for one ABI

### The universal-APK trap

A universal APK carries ONNX Runtime for **four** ABIs — roughly 68 MB of
native code, most of it for devices the app will never run on. That is the
difference between 125 MB and 72 MB and it is entirely avoidable.

**Integrators should ship an App Bundle or per-ABI splits.** Google Play
delivers only the matching ABI, so a real user download is closer to the 72 MB
figure, less Play's compression. Emran's build now enables ABI splits.

If 31 MB of install size matters more than offline-from-first-launch, the model
can be downloaded on first use instead of bundled — see
[`MODEL_DISTRIBUTION.md`](MODEL_DISTRIBUTION.md).

## Integrated RAM — not yet measured

The standalone RATL benchmark measured **350.7 MB peak RSS** on the Redmi
Note 12 Pro+ 5G, against the 400 MB G6 ceiling. That was a bare instrumentation
process.

**Emran + RATL memory is not simply additive and must not be reported as such.**
Emran is a full Compose application with Room, Glance widgets and an image
loader already resident; the JVM heap, the ONNX arena and the graphics stack
overlap and share pages. The integrated figure could plausibly land either side
of a naive sum, which is exactly why it has to be measured.

`RatlIntegrationMemoryTest` is written and builds. It measures, on the real
device, in one process:

1. baseline RSS with RATL never loaded
2. RSS with the engine constructed but idle, plus model load time
3. RSS during batch assessment at 6 s, 12 s and 20 s
4. RSS during streaming in 500 ms chunks
5. peak RSS from `/proc/self/status VmHWM`
6. RSS after `release()`

### Blocker

Installing Emran on the device fails:

```
INSTALL_FAILED_USER_RESTRICTED: Install canceled by user
```

This is MIUI/HyperOS blocking USB installation of a normal application. RATL's
own instrumentation APK installed fine because `testOnly` APKs are exempt;
Emran's is a real app and is not.

**Fix, on the phone:** Settings → Additional settings → Developer options →
enable **"Install via USB"** (MIUI requires a signed-in Mi account for this,
and sometimes an active SIM). Some builds also have **"USB debugging (Security
settings)"**, which must be enabled to install and grant permissions over USB.

Once that is on:

```bash
cd C:/Users/sammy/emran-ratl
adb install -r -t app/build/outputs/apk/debug/app-arm64-v8a-debug.apk
adb install -r -t app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb shell am instrument -w -r \
  -e class com.example.recitation.RatlIntegrationMemoryTest \
  com.aistudio.imad.salah.tracker.test/androidx.test.runner.AndroidJUnitRunner
adb logcat -d -s RATL-EMRAN:I
```

Until it runs, the honest position is: **integrated RAM is unmeasured, and the
350.7 MB standalone figure is not a substitute for it.**

---

## Installed size — why 24 MB became ~111 MB

Measured on the device:

| Component | Size |
|---|---|
| `base.apk` in `/data/app` | 75.3 MB |
| Extracted model in `files/` | 31.0 MB |
| ART artifacts (odex/vdex/art) | remainder |
| **Total reported by the OS** | **~111 MB** |

**The model is stored twice.** Once inside the APK (31 MB, uncompressed so it
loads fast) and again in `files/`, because `RecitationAssessor` extracts it so
ONNX Runtime can open it by path. Assets inside an APK are not real files, and
the ORT Java API takes a path or a byte array — loading 31 MB as a byte array
would put it on the JVM heap and eat the G6 budget, so extraction is the lesser
evil.

That duplicate 31 MB is the one avoidable component:

| Strategy | APK | On-device total | Offline on first launch |
|---|---|---|---|
| Bundle + extract (current) | 75.3 MB | ~111 MB | yes |
| Download on first use | ~44 MB | ~80 MB | no — needs one fetch |

**Recommendation for Emran: download on first use.** Emran already requires
network for verses and prayer times, so the offline-from-first-launch property
that justifies bundling is not doing much work here, and it saves ~31 MB
permanently. The switch is a change to `RecitationAssessor.modelDir` only; the
hash check is already in place and is what makes a download safe.

For an app that must work offline from the very first launch, bundling remains
the right trade.

## Quran-tab feature

`RecitationCheck` is wired into the Surah view, attached to the first ayah of
the visible page, in **both** reading modes.

The first attempt wired it only into the continuous-mode branch, which is off
by default - so the feature shipped, installed, and was invisible. Emran renders
verses through two separate paths (paged mushaf and continuous), and a feature
added to one of them does not exist for most users. Anything added to this
screen needs both call sites. Scoped to one ayah deliberately: that is the unit RATL's
accuracy was measured on, and a full page is a long recitation to hold in one
breath.

- `RecitationRecorder` — `AudioRecord` at 16 kHz mono, the exact rate RATL
  requires, so nothing is resampled. Capture and inference run off the main
  thread; audio never leaves the device.
- Words render as chips coloured by status. **`UNCERTAIN` is shown as
  "unclear", never as a mistake** — RATL abstains rather than accuse, and the UI
  must not undo that. Since V1 never emits `SUBSTITUTION`, a wrong word also
  surfaces as "unclear" rather than an invented verdict.
- While recording, results are labelled "these may still change". Only the
  result after stopping is settled, per SPEC §5.3.
- `RECORD_AUDIO` is requested at the point of use.
