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
        if ("com.example.livechinesea16.TEST_PAUSE".equals(action)) {
            stopListening();
            Log.i(LOG_TAG, "QA_PAUSED ready=" + ready + " mic=" + listening);
            return START_NOT_STICKY;
        }
        if ("com.example.livechinesea16.TEST_RESUME".equals(action)) {
            startListening();
            Log.i(LOG_TAG, "QA_RESUMED ready=" + ready + " mic=" + listening);
            return START_NOT_STICKY;
        }
        if ("com.example.livechinesea16.TEST_CLOSE".equals(action)) {
            closeOverlayAndStop();
            Log.i(LOG_TAG, "QA overlay close action executed");
            return START_NOT_STICKY;
        }
''' + original)
source.write_text(text)
# Add diagnostic logging to the actual Activity's live transcript update and
# Find Next method, only in the disposable emulator test APK.
ui=base/"java/com/example/livechinesea16/MainActivity.java"
page=ui.read_text()
marker='            renderTranscript(current);'
assert page.count(marker)==1
page=page.replace(marker, marker + '''
            if (current.contains("historyline14")) {
                android.util.Log.i("LiveChineseA16", "QA_MAIN_TEXT hasLatest="
                        + transcriptView.getText().toString().contains("historyline14"));
                if (findInput != null) {
                    findInput.setText("historyline07");
                    findNext();
                    android.text.Spanned styled = (android.text.Spanned) transcriptView.getText();
                    int matches = styled.getSpans(0, styled.length(),
                            android.text.style.BackgroundColorSpan.class).length;
                    android.util.Log.i("LiveChineseA16", "QA_FIND_COUNT="
                            + findCount.getText() + " highlights=" + matches);
                    if (false && fullTranscriptDialog == null) {
                        android.util.Log.i("LiveChineseA16", "QA_DIALOG_BEFORE");
                        openFullTranscript();
                        android.util.Log.i("LiveChineseA16", "QA_DIALOG_AFTER showing="
                                + (fullTranscriptDialog != null && fullTranscriptDialog.isShowing())
                                + " view=" + (fullTranscriptView != null)
                                + " input=" + (fullFindInput != null));
                        if (fullTranscriptView != null && fullFindInput != null) {
                            fullFindInput.setText("historyline07");
                            findInFullTranscript();
                            android.text.Spanned all = (android.text.Spanned) fullTranscriptView.getText();
                            int highlightCount = all.getSpans(0, all.length(),
                                    android.text.style.BackgroundColorSpan.class).length;
                            android.util.Log.i("LiveChineseA16", "QA_FULL_SCREEN showing="
                                    + fullTranscriptDialog.isShowing() + " hasLatest="
                                    + all.toString().contains("historyline14")
                                    + " highlights=" + highlightCount);
                        }
                    }
                }
            }
''')
# Validate the full-screen dialog only after the Activity has returned to
# the foreground and its bound service has supplied the latest transcript.
flag='public final class MainActivity extends Activity implements CaptionService.Listener {'
assert page.count(flag)==1
page=page.replace(flag,flag+'\n    private boolean qaDialogChecked = false;')
ready='        if (callModeStatus != null) refreshCallModeStatus();'
assert page.count(ready)==1
page=page.replace(ready,ready+'''
        new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> {
            if (!qaDialogChecked && lastRenderedTranscript.contains("historyline14")) {
                qaDialogChecked = true;
                openFullTranscript();
                if (fullTranscriptDialog != null && fullFindInput != null &&
                        fullTranscriptView != null) {
                    fullFindInput.setText("historyline07");
                    findInFullTranscript();
                    android.text.Spanned styled = (android.text.Spanned) fullTranscriptView.getText();
                    int count = styled.getSpans(0, styled.length(),
                            android.text.style.BackgroundColorSpan.class).length;
                    android.util.Log.i("LiveChineseA16", "QA_FULL_SCREEN showing="
                            + fullTranscriptDialog.isShowing() + " hasLatest="
                            + styled.toString().contains("historyline14")
                            + " highlights=" + count);
                } else {
                    android.util.Log.e("LiveChineseA16", "QA_FULL_SCREEN UI not attached");
                }
            }
        }, 1500);
''')
ui.write_text(page)
manifest=base/"AndroidManifest.xml"
value=manifest.read_text()
a='<service android:name=".CaptionService" android:exported="false"'
b='<service android:name=".CaptionService" android:exported="true"'
assert value.count(a)==1
manifest.write_text(value.replace(a,b))
print('QA-only synthetic transcript insertion and service close hooks enabled for emulator build')
