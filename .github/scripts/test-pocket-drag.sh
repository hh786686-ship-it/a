#!/usr/bin/env bash
set -euo pipefail
adb install -r pocket-drag/output/PocketHighlighter_v1_3.apk
adb shell am start -W -n com.pockethighlighter.reader/com.pockethighlighter.ReaderActivity
sleep 3
adb shell uiautomator dump /sdcard/initial.xml
adb shell cat /sdcard/initial.xml > initial.xml
python3 - <<'PY'
import xml.etree.ElementTree as ET
nodes=list(ET.parse("initial.xml").iter("node"))
descs=[n.get('content-desc','') for n in nodes]
assert "Toggle highlighter on right side" in descs,descs
assert "Eraser: remove highlights only" in descs,descs
print("PASS: Android app installed and both tool buttons visible")
PY
python3 - <<'PY' > mark_coords.txt
import xml.etree.ElementTree as ET,re
for n in ET.parse('initial.xml').iter('node'):
    if n.get('content-desc')=="Toggle highlighter on right side":
        a,b,c,d=map(int,re.findall(r'\d+',n.get('bounds')))
        print((a+c)//2,(b+d)//2)
        break
else: raise AssertionError('Highlighter button not found')
PY
adb shell input tap $(cat mark_coords.txt)
sleep 2
adb shell uiautomator dump /sdcard/before.xml
adb shell cat /sdcard/before.xml > before.xml
python3 - <<'PY' > drag_coords.txt
import xml.etree.ElementTree as ET,re
nodes=list(ET.parse('before.xml').iter('node'))
desc=[n.get('content-desc','') for n in nodes]
for name in ['Drag highlight color panel','Yellow highlighter','Orange highlighter','Grey highlighter','Red highlighter','Highlighter thickness','Undo last highlight on this page']:
    assert name in desc,(name,desc)
for n in nodes:
    if n.get('content-desc')=='Drag highlight color panel':
        a,b,c,d=map(int,re.findall(r'\d+',n.get('bounds')))
        print((a+c)//2,(b+d)//2)
        break
else: raise AssertionError('Draggable header not present')
PY
read X Y < drag_coords.txt
adb shell input swipe "$X" "$Y" "$((X+100))" "$((Y+140))" 700
sleep 2
adb shell uiautomator dump /sdcard/after.xml
adb shell cat /sdcard/after.xml > after.xml
python3 - <<'PY'
import xml.etree.ElementTree as ET,re
def rect(path):
    for n in ET.parse(path).iter('node'):
        if n.get('content-desc')=='Drag highlight color panel':
            return tuple(map(int,re.findall(r'\d+',n.get('bounds'))))
    raise AssertionError("Drag header disappeared")
before=rect('before.xml')
after=rect('after.xml')
dx=after[0]-before[0]
dy=after[1]-before[1]
print("Drag displacement in pixels:",dx,dy)
assert dx>=35 and dy>=50, f"Palette did not move as dragged: {before} -> {after}"
print("PASS: floating palette moves in BOTH X and Y under a real emulator swipe")
nodes=list(ET.parse('after.xml').iter('node'))
names=[n.get('content-desc','') for n in nodes]
assert 'Yellow highlighter' in names and 'Highlighter thickness' in names
assert 'Disable highlighter' in names and 'Eraser: remove highlights only' in names
print("PASS: palette tools and separate pen/eraser controls remain accessible after dragging")
PY
adb logcat -d -t 4000 | grep -E 'FATAL EXCEPTION.*com.pockethighlighter.reader' && exit 1 || true
echo 'PASS: no fatal app exception during UI test'
