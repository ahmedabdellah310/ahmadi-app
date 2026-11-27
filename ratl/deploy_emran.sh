#!/usr/bin/env bash
# Clean up the packages installed during RATL development, then install the
# real Emran with the recitation feature.
#
# Safety: this does NOT uninstall io.github.sammynashed.emran. It attempts an
# in-place upgrade, which preserves app data. If the signing certificate does
# not match, the script STOPS rather than uninstalling - removing the app would
# wipe prayer history and bookmarks, and that has to be a deliberate choice.
set -uo pipefail

export MSYS_NO_PATHCONV=1
export MSYS2_ARG_CONV_EXCL="*"

ADB="${LOCALAPPDATA}/Android/Sdk/platform-tools/adb.exe"
EMRAN="io.github.sammynashed.emran"
PROJ="/c/Android Projects/Emran/emran"
APK="$PROJ/app/build/outputs/apk/debug/app-debug.apk"
MODEL="/c/Users/sammy/ratl/models/out/ratl-align-v1-e2e-fp32.onnx"

echo "== device =="
"$ADB" devices -l
state=$("$ADB" get-state 2>/dev/null || echo "none")
if [ "$state" != "device" ]; then
  echo
  echo "Device is '$state', not 'device'."
  echo "On the phone: unlock the screen and accept 'Allow USB debugging'."
  echo "If no prompt appears: Developer options -> Revoke USB debugging"
  echo "authorisations, then replug and tap Allow."
  exit 1
fi

echo
echo "== removing the packages installed during development =="
for p in com.aistudio.imad.salah.tracker \
         com.aistudio.imad.salah.tracker.test \
         ai.ratl.android.test ; do
  if "$ADB" shell pm list packages 2>/dev/null | tr -d '\r' | grep -q "^package:$p$"; then
    echo "  uninstalling $p"
    "$ADB" uninstall "$p" 2>&1 | sed 's/^/    /'
  else
    echo "  $p not present"
  fi
done

echo
echo "== signature check before upgrading Emran =="
installed=$("$ADB" shell pm list packages 2>/dev/null | tr -d '\r' | grep -c "^package:$EMRAN$")
if [ "$installed" -gt 0 ]; then
  echo "  $EMRAN is installed; attempting an in-place upgrade (data preserved)."
else
  echo "  $EMRAN is not installed; this will be a fresh install."
fi

echo
echo "== installing Emran with the recitation feature =="
if ! "$ADB" install -r -t "$APK" 2>&1 | tee /tmp/ratl_emran_install.log | sed 's/^/  /'; then
  :
fi
if grep -q "INSTALL_FAILED_UPDATE_INCOMPATIBLE\|signatures do not match" /tmp/ratl_emran_install.log; then
  echo
  echo "STOPPED: the debug build is signed with a different key than the copy"
  echo "already on the phone. Installing would require uninstalling first, which"
  echo "ERASES prayer history, bookmarks and settings."
  echo
  echo "Options:"
  echo "  * build and run from Android Studio, where the signing already matches"
  echo "  * or, if the data is expendable:"
  echo "      adb uninstall $EMRAN && adb install -r -t \"$APK\""
  exit 2
fi

echo
echo "== granting the microphone permission =="
"$ADB" shell pm grant "$EMRAN" android.permission.RECORD_AUDIO 2>&1 | sed 's/^/  /'

echo
echo "== copying the model to Downloads so it can be imported =="
# The feature imports the model through the system file picker; putting it in
# Downloads means there is nothing to fetch in order to try it.
"$ADB" push "$MODEL" /sdcard/Download/ 2>&1 | tail -1 | sed 's/^/  /'

echo
echo "== done =="
"$ADB" shell pm list packages 2>/dev/null | tr -d '\r' | grep -iE "emran|ratl|salah" | sed 's/^/  /'
echo
echo "On the phone: open a Surah, find 'Check your recitation',"
echo "tap 'Add recitation model' and pick ratl-align-v1-e2e-fp32.onnx"
echo "from Downloads. Then recite."
