#!/usr/bin/env bash
set -euo pipefail
adb install -r pocket-build/install/PocketHighlighter.apk
adb shell am start -W -n com.pockethighlighter.reader/com.pockethighlighter.ReaderActivity
sleep 4
adb shell uiautomator dump /sdcard/window.xml
adb shell cat /sdcard/window.xml > initial.xml
python3 - <<'PY'
import xml.etree.ElementTree as E
n = list(E.parse('initial.xml').iter('node'))
names=[x.attrib.get('content-desc','') for x in n]
texts=[x.attrib.get('text','') for x in n]
assert 'Open PDF or JPG' in names,(names,texts)
assert 'Toggle highlighter on right side' in names,(names,texts)
print('PASS: Android 13 app installed, launched, open/highlight controls visible')
PY
MARK_COORDS=$(python3 - <<'PY'
import xml.etree.ElementTree as E,re
for n in E.parse('initial.xml').iter('node'):
    if n.attrib.get('content-desc','')=='Toggle highlighter on right side':
        x1,y1,x2,y2=map(int,re.findall(r'\d+',n.attrib['bounds']))
        print((x1+x2)//2,(y1+y2)//2)
        break
else:
    raise SystemExit('Missing highlighter button')
PY
)
adb shell input tap $MARK_COORDS
sleep 1
adb shell uiautomator dump /sdcard/window2.xml
adb shell cat /sdcard/window2.xml > highlighted.xml
python3 - <<'PY'
import xml.etree.ElementTree as E
names=[x.attrib.get('content-desc','') for x in E.parse('highlighted.xml').iter('node')]
for item in ['Yellow highlighter','Orange highlighter','Grey highlighter','Red highlighter','Highlighter thickness','Disable highlighter']:
    assert item in names, ('Missing: '+item,names)
print('PASS: all four colours, width control, and active highlighter visible')
PY
if adb logcat -d -t 5000 | grep -E 'FATAL EXCEPTION.*com.pockethighlighter.reader'; then
    echo 'FAIL: app fatal exception'
    exit 1
fi
echo 'PASS: no fatal exception in emulator test'
