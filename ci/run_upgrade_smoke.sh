#!/usr/bin/env bash
set -euo pipefail

mkdir -p upgrade-evidence
collect_evidence() {
  adb pull /sdcard/Android/data/io.github.cgm2026.operitrpg/files/upgrade-review upgrade-evidence/upgrade-review || true
  adb exec-out screencap -p > upgrade-evidence/d20-upgraded.png || true
  adb logcat -d -b crash > upgrade-evidence/crash-log.txt || true
  adb shell uiautomator dump /sdcard/upgrade-window.xml || true
  adb pull /sdcard/upgrade-window.xml upgrade-evidence/window.xml || true
}
trap collect_evidence EXIT
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb install -r -t app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb shell am instrument -w -r -e class com.ai.assistance.operit.rpg.agent.data.AgentDatabaseTest \
  io.github.cgm2026.operitrpg.debug.test/androidx.test.runner.AndroidJUnitRunner | tee upgrade-evidence/agent-database-instrumentation.txt
grep -Eq '^OK \([1-9][0-9]* tests?\)' upgrade-evidence/agent-database-instrumentation.txt
adb install upgrade-fixture/build/outputs/apk/release/upgrade-fixture-release.apk
adb shell am start -W -n io.github.cgm2026.operitrpg/com.ai.assistance.operit.upgradefixture.FixtureActivity
ready=false
for attempt in $(seq 1 30); do
  if adb shell run-as io.github.cgm2026.operitrpg cat files/upgrade-fixture-ready.txt 2>/dev/null | tr -d '\r' | grep -q '^READY_V1$'; then
    ready=true
    break
  fi
  sleep 1
done
test "$ready" = true
adb shell am force-stop io.github.cgm2026.operitrpg

# Keep package data and require Android to accept the same-signer version increase.
adb install -r previous/operit-rpg-0.2.0.apk
# Preserve fixture bytes so the next test can compare them before any recovery runs.
adb install -r "$D20_UPGRADE_APK_PATH"
adb install -r -t upgrade-tests/build/outputs/apk/release/upgrade-tests-release.apk
adb shell am instrument -w -r -e class com.ai.assistance.operit.rpg.upgrade.UpgradeCompatibilityTest \
  io.github.cgm2026.operitrpg.test/androidx.test.runner.AndroidJUnitRunner | tee upgrade-evidence/instrumentation.txt
grep -Eq '^OK \([1-9][0-9]* tests?\)' upgrade-evidence/instrumentation.txt
adb shell am instrument -w -r -e class 'com.ai.assistance.operit.rpg.upgrade.AgentBlackBoxTest#newAgentFlowSurvivesActivityRelaunch' \
  io.github.cgm2026.operitrpg.test/androidx.test.runner.AndroidJUnitRunner | tee upgrade-evidence/agent-ui-instrumentation.txt
grep -Eq '^OK \([1-9][0-9]* tests?\)' upgrade-evidence/agent-ui-instrumentation.txt
adb shell am force-stop io.github.cgm2026.operitrpg
adb shell am instrument -w -r -e class 'com.ai.assistance.operit.rpg.upgrade.AgentBlackBoxTest#persistedAgentCampaignSurvivesFreshProcess' \
  io.github.cgm2026.operitrpg.test/androidx.test.runner.AndroidJUnitRunner | tee upgrade-evidence/agent-restart-instrumentation.txt
grep -Eq '^OK \([1-9][0-9]* tests?\)' upgrade-evidence/agent-restart-instrumentation.txt
