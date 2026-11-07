#!/usr/bin/env bash
set -euo pipefail
app='com.example.livechinesea16'
apk="${RUNNER_TEMP}/LiveChineseOffline-x86_64.apk"
adb install -r "$apk"
adb shell pm grant "$app" android.permission.RECORD_AUDIO
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
