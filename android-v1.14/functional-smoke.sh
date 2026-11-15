#!/usr/bin/env bash
# End-to-end Android emulator UI/service regression test of v1.11.
# This script is only run against a CI-only x86_64 APK with an injected test transcript action.
set -euo pipefail
trap 'echo "TEST FAILED at line $LINENO ($BASH_COMMAND)"; if [ -f /tmp/livechinese-ui.xml ]; then tail -c 1800 /tmp/livechinese-ui.xml; fi' ERR
app=com.example.livechinesea16
apk="$RUNNER_TEMP/LiveChineseOffline-qa-x86_64.apk"
adb install -r "$apk"
adb shell pm grant "$app" android.permission.RECORD_AUDIO
adb shell appops set "$app" SYSTEM_ALERT_WINDOW deny
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
echo "TEST 1: Android Go overlay permission denied, real offline model loads"
wait_for 'Offline model ready'
echo 'PASS: offline model loaded while overlay blocked'

echo 'TEST 2: Start listening still works without overlay'
tap_text 'Start listening'
sleep 6
adb logcat -d -v brief -s 'LiveChineseA16:*' > /tmp/nooverlay.log
if ! grep -q 'QA_NO_OVERLAY continuing with in-app live transcription' /tmp/nooverlay.log; then
 echo 'FAIL: no overlay denied fallback recorded'; cat /tmp/nooverlay.log; exit 1
fi
if ! grep -q 'AudioRecord state after start: 3' /tmp/nooverlay.log; then
 echo 'FAIL: mic did not start with overlay denied'; cat /tmp/nooverlay.log; exit 1
fi
dump
if ! grep -q 'Stop listening' /tmp/livechinese-ui.xml; then
 echo 'FAIL: Start button did not change to Stop listening'; cat /tmp/livechinese-ui.xml; exit 1
fi
echo 'PASS: actual microphone recording with overlay denied'

echo 'TEST 3: main transcript updates without overlay permission'
for n in $(seq -w 1 14); do
 adb shell am startservice -n "$app/.CaptionService" -a "$app.TEST_INSERT" --es text "historyline$n" >/dev/null
done
sleep 4
adb logcat -d -v brief -s 'LiveChineseA16:*' > /tmp/nooverlay.log
if ! grep -q 'QA_MAIN_TEXT hasLatest=true' /tmp/nooverlay.log; then
 echo 'FAIL: in-app full transcript not updated'; cat /tmp/nooverlay.log; exit 1
fi
if ! grep -q 'QA_FIND_COUNT=1 matches' /tmp/nooverlay.log; then
 echo 'FAIL: in-app find search not functioning'; cat /tmp/nooverlay.log; exit 1
fi
echo 'PASS: in-app live transcript and Find work with overlay denied'

echo 'TEST 4: Stop listening works without an overlay'
adb shell am startservice -n "$app/.CaptionService" -a "$app.TEST_PAUSE" >/dev/null
sleep 4
adb logcat -d -v brief -s 'LiveChineseA16:*' > /tmp/nooverlay.log
if ! grep -q 'QA_PAUSED ready=true mic=false' /tmp/nooverlay.log; then
 echo 'FAIL: microphone did not pause'; cat /tmp/nooverlay.log; exit 1
fi
echo 'PASS: microphone paused'

echo 'TEST 5: no fatal Android exceptions'
if adb logcat -d -v brief | grep -q 'FATAL EXCEPTION'; then
 echo 'FAIL: exception'; adb logcat -d -t 600 | tail -250; exit 1
fi
echo 'PASS: no Android fatal exceptions'
