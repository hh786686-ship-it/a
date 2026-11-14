#!/usr/bin/env python3
"""Add test-only service commands to x86 emulator build. NOT included in release APK."""
from pathlib import Path
base=Path("LiveChineseStream-A16/app/src/main")
source=base/"java/com/example/livechinesea16/CaptionService.java"
text=source.read_text()
original='        if (ACTION_CLOSE.equals(action)) {'
assert text.count(original)==1
text=text.replace(original,'''        if ("com.example.livechinesea16.TEST_INSERT".equals(action)) {
            String synthetic = intent.getStringExtra("text");
            if (synthetic != null) commit(synthetic);
            Log.i(LOG_TAG, "QA synthetic transcript inserted");
            return START_NOT_STICKY;
        }
        if ("com.example.livechinesea16.TEST_INSPECT".equals(action)) {
            Log.i(LOG_TAG, "QA_STATE panel=" + (panel != null)
                    + " back=" + overlayScrollBack
                    + " mic=" + listening
                    + " text=" + (captions == null ? "<none>" : captions.getText().toString())
                    + " full=" + getTranscript());
            return START_NOT_STICKY;
        }
        if ("com.example.livechinesea16.TEST_SCROLL".equals(action)) {
            if (captions != null) {
                long now = SystemClock.uptimeMillis();
                float x = Math.max(10f, captions.getWidth() / 2f);
                android.view.MotionEvent down = android.view.MotionEvent.obtain(
                        now, now, MotionEvent.ACTION_DOWN, x, 20f, 0);
                android.view.MotionEvent move = android.view.MotionEvent.obtain(
                        now, now + 160, MotionEvent.ACTION_MOVE, x, 100f, 0);
                android.view.MotionEvent up = android.view.MotionEvent.obtain(
                        now, now + 200, MotionEvent.ACTION_UP, x, 100f, 0);
                captions.dispatchTouchEvent(down);
                captions.dispatchTouchEvent(move);
                captions.dispatchTouchEvent(up);
                down.recycle(); move.recycle(); up.recycle();
                Log.i(LOG_TAG, "QA_SCROLL back=" + overlayScrollBack
                        + " text=" + captions.getText());
            }
            return START_NOT_STICKY;
        }
        if ("com.example.livechinesea16.TEST_CLOSE".equals(action)) {
            closeOverlayAndStop();
            Log.i(LOG_TAG, "QA overlay close action executed");
            return START_NOT_STICKY;
        }
''' + original)
source.write_text(text)
manifest=base/"AndroidManifest.xml"
value=manifest.read_text()
a='<service android:name=".CaptionService" android:exported="false"'
b='<service android:name=".CaptionService" android:exported="true"'
assert value.count(a)==1
manifest.write_text(value.replace(a,b))
print('QA-only synthetic transcript insertion and service close hooks enabled for emulator build')
