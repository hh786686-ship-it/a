#!/usr/bin/env bash
# End-to-end Android emulator UI/service regression test of v1.10.
# This script is only run against a CI-only x86_64 APK with an injected test transcript action.
set -euo pipefail
trap 'echo "TEST FAILED at line $LINENO ($BASH_COMMAND)"; if [ -f /tmp/livechinese-ui.xml ]; then tail -c 1800 /tmp/livechinese-ui.xml; fi' ERR
app=com.example.livechinesea16
apk="$RUNNER_TEMP/LiveChineseOffline-qa-x86_64.apk"
adb install -r "$apk"
adb shell pm grant "$app" android.permission.RECORD_AUDIO
adb shell appops set "$app" SYSTEM_ALERT_WINDOW allow
adb shell settings put system font_scale 1.0
adb shell input keyevent KEYCODE_WAKEUP
adb shell wm dismiss-keyguard || true
adb logcat -c
adb shell am start -n "$app/.MainActivity"
dump() {
  adb shell uiautomator dump /sdcard/uidump.xml >/dev/null 2>&1 || true
  adb shell cat /sdcard/uidump.xml > /tmp/livechinese-ui.xml
}
wait_for() {
  local needle="$1"
  for i in {1..45}; do
    dump
    if grep -q "$needle" /tmp/livechinese-ui.xml; then return 0; fi
    sleep 3
  done
  echo "FAIL: UI did not show $needle"; cat /tmp/livechinese-ui.xml; adb logcat -d -t 400 | tail -180
  exit 1
}
tap_text() {
  local label="$1"
  dump
  python3 - "$label" <<'PY'
import html,re,sys
label=sys.argv[1]
s=html.unescape(open('/tmp/livechinese-ui.xml').read())
for node in re.findall(r'<node [^>]*>',s):
    if ('text="'+label+'"') in node:
        m=re.search(r'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"',node)
        if m:
            a,b,c,d=map(int,m.groups())
            open('/tmp/tapcoords','w').write(f'{(a+c)//2} {(b+d)//2}\n')
            sys.exit(0)
raise SystemExit("Cannot find button: "+label)
PY
  read -r x y < /tmp/tapcoords
  adb shell input tap "$x" "$y"
}
echo "TEST 1: on-device model loads in an Android emulator"
wait_for 'Offline model ready'
echo 'PASS: real model initialised'

echo "TEST 2: microphone starts with an overlay"
tap_text 'Start listening'
sleep 5
adb logcat -d -v brief -s 'LiveChineseA16:*' > /tmp/livechinese-events.log
grep -q 'AudioRecord state after start: 3' /tmp/livechinese-events.log || {
 echo 'FAIL: microphone did not reach RECORDSTATE_RECORDING'; tail -100 /tmp/livechinese-events.log; exit 1; }
echo 'PASS: microphone recording state'

echo 'TEST 3: inject deterministic completed lines into same running service'
for n in $(seq -w 1 14); do
 adb shell am startservice -n "$app/.CaptionService" -a "$app.TEST_INSERT" --es text "historyline$n" >/dev/null
done
sleep 3
# The AOSP ATD fake Home Screen isn't exposed together with overlay windows by
# UIAutomator, so inspect the real service TextView directly via QA-only command.
adb shell input keyevent KEYCODE_HOME
sleep 2
adb logcat -c
adb shell am startservice -n "$app/.CaptionService" -a "$app.TEST_INSPECT" >/dev/null
sleep 1
adb logcat -d -v brief -s 'LiveChineseA16:*' > /tmp/qa-overlay.log
if ! grep -q 'QA_STATE panel=true' /tmp/qa-overlay.log ||
   ! grep -q 'historyline14' /tmp/qa-overlay.log; then
 echo 'FAIL: overlay text did not update with synthetic transcript'
 cat /tmp/qa-overlay.log; exit 1
fi
echo 'PASS: overlay TextView updated while Home is visible'

echo 'TEST 4: subtitle touch gesture scrolls to earlier lines'
adb shell am startservice -n "$app/.CaptionService" -a "$app.TEST_SCROLL" >/dev/null
sleep 1
adb logcat -d -v brief -s 'LiveChineseA16:*' > /tmp/qa-overlay.log
if ! grep -Eq 'QA_SCROLL back=[1-9]' /tmp/qa-overlay.log; then
 echo 'FAIL: gesture did not increase overlay scrollback offset'; cat /tmp/qa-overlay.log; exit 1
fi
echo 'PASS: overlay caption touch handler scrolls backwards'

echo 'TEST 5: returning to Activity updates full transcript TextView'
adb logcat -c
adb shell am start -n "$app/.MainActivity"
sleep 4
adb logcat -d -v brief -s 'LiveChineseA16:*' > /tmp/qa-main.log
if ! grep -q 'QA_MAIN_TEXT hasLatest=true' /tmp/qa-main.log; then
 echo 'FAIL: MainActivity transcript TextView not updated with live service text'; cat /tmp/qa-main.log; exit 1
fi
echo 'PASS: full transcript TextView has newest completed line'

echo 'TEST 6: Find Next highlights matching transcript text'
if ! grep -Eq 'QA_FIND_COUNT=[1-9][0-9]* matches.*highlights=[1-9]' /tmp/qa-main.log; then
 echo 'FAIL: Find Next did not find and highlight the test word'; cat /tmp/qa-main.log; exit 1
fi
echo 'PASS: Find Next reports matches and applies a visible highlight'

echo 'TEST 7: pause mic; restart using same loaded model'
adb logcat -c
adb shell am startservice -n "$app/.CaptionService" -a "$app.TEST_PAUSE" >/dev/null
sleep 4
adb shell am startservice -n "$app/.CaptionService" -a "$app.TEST_RESUME" >/dev/null
sleep 3
adb logcat -d -v brief -s 'LiveChineseA16:*' > /tmp/qa-pause.log
if ! grep -q 'QA_PAUSED ready=true mic=false' /tmp/qa-pause.log; then
 echo 'FAIL: pause did not stop the microphone or service was already destroyed'; cat /tmp/qa-pause.log; exit 1
fi
if ! grep -q 'QA_RESUMED ready=true mic=true' /tmp/qa-pause.log; then
 echo 'FAIL: resume did not restart using a loaded model'; cat /tmp/qa-pause.log; exit 1
fi
echo 'PASS: microphone paused and restarted without reinitializing model'

echo 'TEST 8: close overlay and verify recording service stops'
adb shell am startservice -n "$app/.CaptionService" -a "$app.TEST_CLOSE" >/dev/null
sleep 6
dump
if grep -q 'Overlay pinned' /tmp/livechinese-ui.xml; then echo 'NOTE: notification may still be cached'; fi
if adb shell dumpsys activity services "$app" | grep -E 'isForeground=true|foregroundId=217'; then
 echo 'FAIL: microphone foreground service still running after overlay closure'; exit 1
fi
echo 'PASS: foreground capture service stopped'
if adb logcat -d -v brief | grep -q 'FATAL EXCEPTION'; then
 echo 'FAIL: fatal Android exception in emulator'; adb logcat -d -t 500 | tail -200; exit 1
fi
echo 'PASS: no Android fatal exceptions'
