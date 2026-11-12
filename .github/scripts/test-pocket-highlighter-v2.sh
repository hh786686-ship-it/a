#!/usr/bin/env bash
set -euo pipefail
adb install -r pocket-v2/install/PocketHighlighter_v1_1.apk
adb shell am start -W -n com.pockethighlighter.reader/com.pockethighlighter.ReaderActivity
sleep 4
adb shell uiautomator dump /sdcard/window.xml
adb shell cat /sdcard/window.xml > initial.xml
python3 - <<'PY'
import xml.etree.ElementTree as E
n = list(E.parse('initial.xml').iter('node'))
names=[x.attrib.get('content-desc','') for x in n]
texts=[x.attrib.get('text','') for x in n]
assert 'Open PDF, JPG, PNG or DOCX' in names,(names,texts)
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

# Press Android Back: the app should move to background, not destroy the session.
adb shell input keyevent 4
sleep 2
adb shell am start -W -n com.pockethighlighter.reader/com.pockethighlighter.ReaderActivity
sleep 2
adb shell uiautomator dump /sdcard/window3.xml
adb shell cat /sdcard/window3.xml > reopened.xml
python3 - <<'PY'
import xml.etree.ElementTree as E
names=[x.attrib.get('content-desc','') for x in E.parse('reopened.xml').iter('node')]
assert 'Open PDF, JPG, PNG or DOCX' in names,names
assert 'Toggle highlighter on right side' in names or 'Disable highlighter' in names,names
print('PASS: Back and reopen keep reading UI available')
PY
