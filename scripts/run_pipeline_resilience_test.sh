#!/usr/bin/env bash
#
# Drives Task 12's End-to-End Pipeline Resilience Test (Paper Mode) as a
# single command, working around the one constraint no in-process test
# runner can work around: standard Android instrumentation always shares
# its process with the app under test, so a genuinely ungraceful kill
# ("am force-stop") can only be inflicted *between* separate
# `am instrument` invocations, never from inside one running test method
# without also killing the method's own JVM.
#
# See app/src/androidTest/kotlin/org/example/syncora/resilience/package-info.kt
# gap #5 and PipelineResilienceTest's class kdoc for the full reasoning.
#
# Usage:
#   ./scripts/run_pipeline_resilience_test.sh [-s <device-serial>]
#
# Requires: a connected device or running emulator with a debug build
# installable (ENABLE_RESILIENCE_TEST_HARNESS is only true in debug), and
# app/src/androidTest/assets/fixtures/dummy_policy_model.tflite already
# generated - see scripts/generate_dummy_policy_model.py.

set -euo pipefail

APP_ID="org.example.syncora"
TEST_APP_ID="${APP_ID}.test"
RUNNER="androidx.test.runner.AndroidJUnitRunner"
TEST_CLASS="org.example.syncora.resilience.PipelineResilienceTest"
RELAUNCH_WAIT_SECONDS=5

ADB=(adb)
GRADLE="./gradlew"

while getopts "s:" opt; do
  case "$opt" in
    s) ADB=(adb -s "$OPTARG") ;;
    *) echo "Usage: $0 [-s <device-serial>]" >&2; exit 1 ;;
  esac
done

fixture="app/src/androidTest/assets/fixtures/dummy_policy_model.tflite"
if [[ ! -f "$fixture" ]]; then
  echo "Missing $fixture - run scripts/generate_dummy_policy_model.py first (needs TensorFlow installed)." >&2
  exit 1
fi

echo "==> Building debug app + androidTest APKs"
"$GRADLE" :app:assembleDebug :app:assembleDebugAndroidTest

app_apk=$(find app/build/outputs/apk/debug -name "*.apk" | head -n1)
test_apk=$(find app/build/outputs/apk/androidTest/debug -name "*.apk" | head -n1)
if [[ -z "$app_apk" || -z "$test_apk" ]]; then
  echo "Could not locate built APKs under app/build/outputs/apk/" >&2
  exit 1
fi

echo "==> Installing $app_apk"
"${ADB[@]}" install -r -g "$app_apk"
echo "==> Installing $test_apk"
"${ADB[@]}" install -r -g "$test_apk"

# --clear-data before phaseA only: every later phase depends on the
# experience log/model files phaseA and phaseB wrote, and must survive the
# kill that follows, so nothing after phaseA may wipe app data.
echo "==> Clearing app data for a clean phaseA run (empty experience log)"
"${ADB[@]}" shell pm clear "$APP_ID" >/dev/null

run_phase() {
  local method="$1"
  echo
  echo "==> Running $TEST_CLASS#$method"
  local output
  set +e
  output=$("${ADB[@]}" shell am instrument -w -e class "${TEST_CLASS}#${method}" \
    "${TEST_APP_ID}/${RUNNER}" 2>&1)
  local status=$?
  set -e
  echo "$output"
  if [[ $status -ne 0 ]] || echo "$output" | grep -q "FAILURES!!!"; then
    echo
    echo "==> $method FAILED - aborting the run (see output above)."
    exit 1
  fi
  echo "==> $method PASSED"
}

kill_and_relaunch() {
  echo
  echo "==> Ungracefully killing $APP_ID (am force-stop) and relaunching"
  "${ADB[@]}" shell am force-stop "$APP_ID"
  "${ADB[@]}" shell monkey -p "$APP_ID" -c android.intent.category.LAUNCHER 1 >/dev/null
  sleep "$RELAUNCH_WAIT_SECONDS"
}

run_phase "phaseA_day1DecisionsAndFundingSettlement"
kill_and_relaunch
run_phase "phaseB_survivesFirstKillAndResolvesDay2"
kill_and_relaunch
run_phase "phaseC_survivesSecondKillAndTrainingJobSucceeds"

echo
echo "==> All three phases passed. Task 12 exit criteria met (see the design doc's §5 table)."
