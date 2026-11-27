#!/usr/bin/env bash
# M3 - deploy RATL to the SPEC 7.3 benchmark device and measure G5 / G6.
#
# Fixtures go to the app-private external directory, not /sdcard: under scoped
# storage an app can stat /sdcard/ratl but cannot list it, even though adb
# pushed there successfully. That requires the test APK to be installed BEFORE
# the push, so the run is driven with `am instrument` rather than
# `connectedAndroidTest`, and the chosen directory is passed in explicitly.
set -euo pipefail

# Git Bash rewrites absolute POSIX paths into Windows paths on the way to adb.
export MSYS_NO_PATHCONV=1
export MSYS2_ARG_CONV_EXCL="*"

ADB="${LOCALAPPDATA}/Android/Sdk/platform-tools/adb.exe"
GRADLE="$(ls -d ~/.gradle/wrapper/dists/gradle-9.3.1-bin/*/gradle-9.3.1 | head -1)/bin/gradle"
PKG="ai.ratl.android.test"
DIR="/sdcard/Android/data/${PKG}/files/ratl"
APK="ratl-android/build/outputs/apk/androidTest/debug/ratl-android-debug-androidTest.apk"
RUNNER="androidx.test.runner.AndroidJUnitRunner"

echo "== device =="
"$ADB" devices -l
"$ADB" wait-for-device

echo "== build + install the instrumentation APK =="
"$GRADLE" :ratl-android:assembleDebugAndroidTest --console=plain -q
"$ADB" install -r -t "$APK"

echo "== push model and fixtures to ${DIR} =="
"$ADB" shell mkdir -p "${DIR}/model" "${DIR}/pcm"
"$ADB" push models/out/ratl-align-v1-e2e-fp32.onnx "${DIR}/model/"
"$ADB" push models/out/vocab.json "${DIR}/model/"

# A spread of clip lengths across the five bench reciters, so RTF is not
# measured on one convenient duration.
for f in \
  eval/bench_pcm/Husary_64kbps/001001.s16 \
  eval/bench_pcm/Alafasy_128kbps/018001.s16 \
  eval/bench_pcm/Abdul_Basit_Mujawwad_128kbps/001007.s16 \
  eval/bench_pcm/Hani_Rifai_192kbps/036001.s16 \
  eval/bench_pcm/Minshawy_Murattal_128kbps/055001.s16
do
  if [ -f "$f" ]; then
    "$ADB" push "$f" "${DIR}/pcm/"
  else
    echo "  (skip missing $f)"
  fi
done

echo "== run the on-device benchmark =="
"$ADB" shell am instrument -w -r \
  -e class ai.ratl.android.DeviceBenchmarkTest \
  -e fixtures "${DIR}" \
  "${PKG}/${RUNNER}"

# The test writes to internal storage, which adb cannot read directly, so the
# record is recovered from logcat instead.
echo "== results =="
"$ADB" logcat -d -s RATL-BENCH:I | sed 's/^.*RATL-BENCH: //' | tr -d ''   | awk '/^=== DEVICE ===$/{seen=1} seen' | tee models/g5_g6_device.txt
