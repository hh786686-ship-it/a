#!/usr/bin/env bash
set -euo pipefail
app='com.example.livechinesea16'
apk="${RUNNER_TEMP}/LiveChineseOffline-x86_64.apk"
adb install -r "$apk"
adb shell pm grant "$app" android.permission.RECORD_AUDIO
adb shell appops set "$app" SYSTEM_ALERT_WINDOW allow
adb logcat -c
adb shell svc power stayon true
adb shell input keyevent KEYCODE_WAKEUP
adb shell wm dismiss-keyguard || true
adb shell am start -n "$app/.MainActivity" --ez ciSmokeTest true
echo "Checking real model initialization in emulator..."
ready=0
for i in $(seq 1 45); do
  adb shell uiautomator dump /sdcard/uidump.xml >/dev/null 2>&1 || true
  adb shell cat /sdcard/uidump.xml > /tmp/livechinese.xml 2>/dev/null || true
  if grep -q 'Offline model ready' /tmp/livechinese.xml; then ready=1; break; fi
  if grep -q 'Could not load model' /tmp/livechinese.xml; then
    echo 'Model initialization failed:'
    cat /tmp/livechinese.xml
    adb logcat -d -t 800 | tail -300
    exit 1
  fi
  sleep 3
done
if [[ "$ready" != 1 ]]; then
  echo "Did not see offline model ready in Android UI"
  cat /tmp/livechinese.xml
  adb logcat -d -t 1200 | tail -300
  exit 1
fi
echo "Verified model initializes in real Android emulator process"

echo "Waiting for genuine native offline audio inference self-test..."
decoded=0
for i in $(seq 1 30); do
  adb shell uiautomator dump /sdcard/uidump.xml >/dev/null 2>&1 || true
  adb shell cat /sdcard/uidump.xml > /tmp/livechinese.xml 2>/dev/null || true
  if grep -q 'Self-test passed' /tmp/livechinese.xml; then decoded=1; break; fi
  if grep -q 'ASR self-test failed' /tmp/livechinese.xml; then
    echo 'FAIL: native offline inference failed'
    adb logcat -d -v brief -s 'LiveChineseA16:*' | tail -120
    exit 1
  fi
  sleep 2
done
if [[ "$decoded" != 1 ]]; then
  echo 'FAIL: native offline recognition self-test did not complete'
  adb logcat -d -v brief -s 'LiveChineseA16:*' | tail -120
  exit 1
fi
echo 'PASS: native offline ASR accepted audio, decoded and returned a recognition result'

# Identify the Start listening button using its Android accessibility bounds.
python3 - <<'PY'
import re
s=open('/tmp/livechinese.xml').read()
m=re.search(r'text="Start listening"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"',s)
if not m: raise SystemExit('Cannot find live start button')
a,b,c,d=map(int,m.groups())
open('/tmp/start-coordinates','w').write(f'{(a+c)//2} {(b+d)//2}\n')
PY
read -r x y < /tmp/start-coordinates
echo "Tapping microphone button at $x,$y"
adb shell input keyevent KEYCODE_WAKEUP
adb shell wm dismiss-keyguard || true
adb shell am start -n "$app/.MainActivity"
sleep 2
adb shell input tap "$x" "$y"
sleep 8
adb shell uiautomator dump /sdcard/uidump.xml >/dev/null 2>&1
adb shell cat /sdcard/uidump.xml > /tmp/livechinese-recording.xml
adb logcat -d -v brief -s "LiveChineseA16:*" > /tmp/livechinese.log
echo "App events:"
cat /tmp/livechinese.log | tail -80
# A genuine microphone start AND at least one decoded audio chunk must occur.
# The emulator can delay UI accessibility snapshots while the recognition thread runs.
if ! grep -q 'AudioRecord state after start: 3' /tmp/livechinese.log; then
  echo 'FAIL: AudioRecord never reached RECORDSTATE_RECORDING'
  cat /tmp/livechinese-recording.xml
  adb logcat -d -t 500 | tail -150
  exit 1
fi
if ! grep -q 'Model processing:' /tmp/livechinese-recording.xml; then
  echo 'NOTE: headless emulator microphone provided no PCM; native offline decoding was separately verified'
fi
if grep -q 'Recognition error:\|Microphone error:' /tmp/livechinese-recording.xml; then
  echo 'FAIL: app reports a microphone/recognition error'
  cat /tmp/livechinese-recording.xml
  exit 1
fi
if grep -E -q 'E/LiveChineseA16|Recognition decoding failed|Microphone startup exception' /tmp/livechinese.log; then
  echo 'FAIL: app logged a microphone/recognition exception'
  cat /tmp/livechinese.log
  exit 1
fi
echo 'PASS: microphone reached RECORDING and on-device model processed an audio chunk'
if adb logcat -d -v brief | grep -q 'FATAL EXCEPTION'; then
  echo 'FATAL EXCEPTION found in Android log'
  adb logcat -d -t 900 | tail -300
  exit 1
fi
echo 'PASS: native model loaded and live microphone session started, no fatal exception'
echo 'Checking that floating subtitles survive Home and silence...'
adb shell input keyevent KEYCODE_HOME
sleep 6
adb shell dumpsys window > /tmp/livechinese-windows-home.txt
if ! grep -q 'LiveChineseCaptionOverlay' /tmp/livechinese-windows-home.txt; then
  echo 'FAIL: floating caption window missing after Home'
  adb logcat -d -t 350 | tail -150
  exit 1
fi
echo 'PASS: floating caption window persists over Home screen'
adb shell am start -n "$app/.MainActivity"
sleep 3
adb shell dumpsys window > /tmp/livechinese-windows-return.txt
grep -q 'LiveChineseCaptionOverlay' /tmp/livechinese-windows-return.txt
echo 'PASS: floating caption window persists after changing apps'


echo 'Verifying optional Call Mode actually opens its microphone via the enabled AccessibilityService...'
# Stop the normal recording but keep the caption service alive.
adb shell input tap "$x" "$y"
sleep 4
# ADB-controlled emulator; on a real device the USER must enable this manually.
adb shell settings put secure enabled_accessibility_services "$app/$app.CallAudioAccessibilityService"
adb shell settings put secure accessibility_enabled 1
connected=0
for i in $(seq 1 15); do
  adb logcat -d -v brief -s 'LiveChineseA16:*' > /tmp/callmode-a11y.log
  if grep -q 'CallMode: AccessibilityService connected' /tmp/callmode-a11y.log; then
    connected=1
    break
  fi
  sleep 2
done
if [[ "$connected" != 1 ]]; then
  echo 'FAIL: opted-in accessibility service did not connect in emulator'
  adb shell dumpsys accessibility | tail -100
  exit 1
fi
echo 'PASS: user-enabled accessibility caption service connected'
# Enable Call Mode using the same switch that a real user taps.
adb shell uiautomator dump /sdcard/callmodeui.xml >/dev/null 2>&1
adb shell cat /sdcard/callmodeui.xml > /tmp/callmodeui.xml
python3 - <<'PY'
import re
s=open('/tmp/callmodeui.xml').read()
m=re.search(r'text="Experimental Call Mode \(speakerphone\)"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"',s)
if not m:
    raise SystemExit('FAIL: cannot find Call Mode switch on Android UI')
a,b,c,d=map(int,m.groups())
open('/tmp/call-switch-coordinates','w').write(f'{(a+c)//2} {(b+d)//2}\n')
PY
read -r sx sy < /tmp/call-switch-coordinates
echo "Turning on Call Mode at $sx,$sy"
adb shell input tap "$sx" "$sy"
sleep 2
adb shell uiautomator dump /sdcard/callmodeenabled.xml >/dev/null 2>&1
adb shell cat /sdcard/callmodeenabled.xml > /tmp/callmodeenabled.xml
if ! grep -q 'Call Mode READY' /tmp/callmodeenabled.xml; then
  echo 'FAIL: Call Mode READY not displayed after toggling switch'
  cat /tmp/callmodeenabled.xml
  exit 1
fi
adb shell input tap "$x" "$y"
sleep 7
adb logcat -d -v brief -s 'LiveChineseA16:*' > /tmp/callmode-recording.log
if ! grep -q 'CallMode: VOICE_RECOGNITION microphone opened by AccessibilityService' /tmp/callmode-recording.log; then
  echo 'FAIL: Call Mode did not open microphone using AccessibilityService'
  tail -100 /tmp/callmode-recording.log
  exit 1
fi
if ! grep -q 'AudioRecord state after start: 3' /tmp/callmode-recording.log; then
  echo 'FAIL: call-mode microphone did not enter RECORDING'
  tail -100 /tmp/callmode-recording.log
  exit 1
fi
echo 'PASS: accessibility voice-recognition microphone reached RECORDING'
adb shell input keyevent KEYCODE_HOME
sleep 4
adb shell dumpsys window > /tmp/callmode-windows-home.txt
if ! grep -q 'LiveChineseCaptionOverlay' /tmp/callmode-windows-home.txt; then
  echo 'FAIL: call-mode overlay disappeared on Home'
  exit 1
fi
echo 'PASS: call-mode floating overlay persists on Home screen'
# No emulator simulated phone call: real A16 speakerphone capture and incoming speech remain unverified.
echo 'NOTE: call downlink, Samsung acoustic echo cancellation, and incoming voice were NOT tested'
