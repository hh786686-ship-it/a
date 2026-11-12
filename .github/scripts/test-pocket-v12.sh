#!/usr/bin/env bash
set -euo pipefail
adb install -r pocket-v12/install/PocketHighlighter_v1_2.apk
adb shell am start -W -n com.pockethighlighter.reader/com.pockethighlighter.ReaderActivity
sleep 4
adb shell uiautomator dump /sdcard/initial.xml
adb shell cat /sdcard/initial.xml > initial.xml
python3 - <<'PY'
import xml.etree.ElementTree as E
nodes=list(E.parse('initial.xml').iter('node'))
desc=[n.get('content-desc','') for n in nodes]
for item in ['Open PDF or JPG','Toggle highlighter on right side','Eraser: remove highlights only']:
    assert item in desc,(item,desc)
print('PASS: APK installs, launches, and eraser button is visible')
PY
coords=$(python3 - <<'PY'
import xml.etree.ElementTree as E,re
for n in E.parse('initial.xml').iter('node'):
    if n.attrib.get('content-desc')=='Eraser: remove highlights only':
        x1,y1,x2,y2=map(int,re.findall(r'\d+',n.attrib['bounds']))
        print((x1+x2)//2,(y1+y2)//2)
        break
else: raise RuntimeError('Eraser button not found')
PY
)
adb shell input tap $coords
sleep 1
adb shell uiautomator dump /sdcard/eraser.xml
adb shell cat /sdcard/eraser.xml > eraser.xml
python3 - <<'PY'
import xml.etree.ElementTree as E
names=[x.attrib.get('content-desc','') for x in E.parse('eraser.xml').iter('node')]
assert 'Eraser: remove highlights only' in names,names
print('PASS: eraser can be selected without crashing')
PY
adb shell input keyevent 4
sleep 1
adb shell am start -W -n com.pockethighlighter.reader/com.pockethighlighter.ReaderActivity
sleep 2
adb shell uiautomator dump /sdcard/reopened.xml
adb shell cat /sdcard/reopened.xml > reopened.xml
python3 - <<'PY'
import xml.etree.ElementTree as E
names=[x.attrib.get('content-desc','') for x in E.parse('reopened.xml').iter('node')]
assert 'Eraser: remove highlights only' in names,names
print('PASS: Back then reopen returns to working app')
PY
adb logcat -d -t 5000 | grep -E 'FATAL EXCEPTION.*com.pockethighlighter.reader' && exit 1 || true
echo 'PASS: no app fatal exception'
