#!/usr/bin/env bash
set -euo pipefail
app='com.example.livechinesea16'
apk='LiveChineseStream-A16/app/build/outputs/apk/debug/app-debug.apk'
adb install -r "$apk"
adb shell pm grant "$app" android.permission.RECORD_AUDIO
adb logcat -c
adb shell am start -n "$app/.MainActivity"
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
adb shell input tap "$x" "$y"
sleep 8
adb shell uiautomator dump /sdcard/uidump.xml >/dev/null 2>&1
adb shell cat /sdcard/uidump.xml > /tmp/livechinese-recording.xml
if ! grep -q 'Stop and finish' /tmp/livechinese-recording.xml; then
  echo 'Recording did not start successfully'
  cat /tmp/livechinese-recording.xml
  adb logcat -d -t 900 | tail -300
  exit 1
fi
if adb logcat -d -v brief | grep -q 'FATAL EXCEPTION'; then
  echo 'FATAL EXCEPTION found in Android log'
  adb logcat -d -t 900 | tail -300
  exit 1
fi
echo 'PASS: native model loaded and live microphone session started, no fatal exception'
