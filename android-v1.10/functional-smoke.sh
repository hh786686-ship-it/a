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
# Text source is tested on the Home screen overlay, avoiding activity text overlap.
adb shell input keyevent KEYCODE_HOME
sleep 2
dump
if ! grep -q 'historyline14' /tmp/livechinese-ui.xml; then
 echo 'FAIL: latest caption not shown in floating overlay'
 cat /tmp/livechinese-ui.xml; exit 1
fi
echo 'PASS: overlay shows new text from active service'

echo 'TEST 4: swipe down on caption area scrolls earlier subtitle lines'
python3 - <<'PY'
import html,re
s=html.unescape(open('/tmp/livechinese-ui.xml').read())
for node in re.findall(r'<node [^>]*>',s):
 if 'historyline14' in node:
  m=re.search(r'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"',node)
  if m:
   a,b,c,d=map(int,m.groups())
   open('/tmp/swipe','w').write(f'{(a+c)//2} {(b+d)//2}\n')
   break
else:
 raise SystemExit('Cannot locate caption text bounds')
PY
read -r cx cy < /tmp/swipe
# Keep swipe within subtitle window; three small down-swipes reveal earlier lines.
for i in 1 2 3; do adb shell input swipe "$cx" "$cy" "$cx" "$((cy+65))" 300; done
sleep 2
dump
if ! grep -q 'historyline0' /tmp/livechinese-ui.xml; then
 echo 'FAIL: older subtitles not visible after scrolling'; cat /tmp/livechinese-ui.xml; exit 1
fi
echo 'PASS: overlay can show earlier captions'

echo 'TEST 5: return to main activity; full transcript contains latest lines'
adb shell am start -n "$app/.MainActivity"
sleep 2
for i in 1 2 3; do adb shell input swipe 500 1500 500 520 340; done
dump
if ! grep -q 'historyline14' /tmp/livechinese-ui.xml; then
 echo 'FAIL: live transcript not updated in Activity'; cat /tmp/livechinese-ui.xml; exit 1
fi
echo 'PASS: main Activity transcript is live'

echo 'TEST 6: search with Find next'
if ! grep -q 'Find next' /tmp/livechinese-ui.xml; then
 echo 'FAIL: Find next control absent'; cat /tmp/livechinese-ui.xml; exit 1
fi
python3 - <<'PY'
import html,re
s=html.unescape(open('/tmp/livechinese-ui.xml').read())
for node in re.findall(r'<node [^>]*>',s):
 if 'Find words in transcript' in node:
  m=re.search(r'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"',node)
  if m:
   a,b,c,d=map(int,m.groups())
   open('/tmp/findcoords','w').write(f'{(a+c)//2} {(b+d)//2}\n')
   break
else: raise SystemExit('Cannot find search input')
PY
read -r fx fy < /tmp/findcoords
adb shell input tap "$fx" "$fy"
adb shell input text historyline07
adb shell input keyevent KEYCODE_BACK
tap_text 'Find next'
sleep 2
dump
if ! grep -q 'matches' /tmp/livechinese-ui.xml; then
 echo 'FAIL: search did not report match count'; cat /tmp/livechinese-ui.xml; exit 1
fi
echo 'PASS: Find next shows search match count'

echo 'TEST 7: stop listening and restart without model reload'
# Bring Start/Stop button into view.
for i in 1 2 3; do adb shell input swipe 500 500 500 1550 280; done
sleep 2
tap_text 'Stop listening'
wait_for 'Start listening'
if grep -q 'Loading model' /tmp/livechinese-ui.xml; then echo 'FAIL: reloaded model on pause'; exit 1; fi
echo 'PASS: microphone paused, model remains ready'
tap_text 'Start listening'
sleep 4
adb logcat -d -v brief -s 'LiveChineseA16:*' | grep -c 'AudioRecord state after start: 3' | awk '{if ($1 < 2) exit 1}'
echo 'PASS: restarted microphone without reloading the recognizer'

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
