package com.example.livechinesea16;

import android.Manifest;
import android.app.Activity;
import android.app.Dialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.os.IBinder;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.view.MotionEvent;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.style.BackgroundColorSpan;
import android.text.style.ForegroundColorSpan;
import java.util.Locale;
import android.widget.Switch;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

public final class MainActivity extends Activity implements CaptionService.Listener {
    private static final int MIC_PERMISSION = 42;
    private static final int SAVE_DOCUMENT = 43;
    private CaptionService captions;
    private boolean bound;
    private boolean selfTestRequested;
    private TextView statusView, metricView, transcriptView, widthLabel, linesLabel, fontLabel;
    private ScrollView transcriptScroll;
    private Dialog fullTranscriptDialog;
    private TextView fullTranscriptView;
    private ScrollView fullTranscriptScroll;
    private EditText fullFindInput;
    private TextView fullFindCount;
    private int fullMatchIndex = -1;
    private String fullLastQuery = "";
    private String lastFullText = "";
    private EditText findInput;
    private TextView findCount;
    private String lastRenderedTranscript = "";
    private String lastFindTerm = "";
    private int currentMatch = -1;
    private Button listenButton;
    private Switch callModeSwitch;
    private Switch keepReadySwitch;
    private TextView callModeStatus;
    private SeekBar widthBar, linesBar, fontBar;
    private boolean updatingSliders;

    private final ServiceConnection connection = new ServiceConnection() {
        @Override public void onServiceConnected(ComponentName n, IBinder b) {
            captions = ((CaptionService.LocalBinder) b).service();
            bound = true;
            captions.setListener(MainActivity.this);
            if (selfTestRequested) {
                selfTestRequested = false;
                captions.requestSelfTest();
            }
            onChanged();
        }
        @Override public void onServiceDisconnected(ComponentName name) {
            bound = false;
            captions = null;
            statusView.setText("Caption service disconnected");
            listenButton.setEnabled(false);
        }
    };

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().setStatusBarColor(Color.rgb(14, 24, 39));
        getWindow().setNavigationBarColor(Color.rgb(14, 24, 39));
        selfTestRequested = getIntent().getBooleanExtra("ciSmokeTest", false);
        makeUi();
    }

    @Override protected void onStart() {
        super.onStart();
        bindService(new Intent(this, CaptionService.class), connection, BIND_AUTO_CREATE);
    }
    @Override protected void onResume() {
        super.onResume();
        // Prevent display timeout while this Activity is visible. Does not use
        // a CPU wakelock or keep the screen awake after switching applications.
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        if (callModeStatus != null) refreshCallModeStatus();
        if (keepReadySwitch != null) {
            keepReadySwitch.setChecked(getSharedPreferences(CaptionService.PREFS, MODE_PRIVATE)
                    .getBoolean(CaptionService.KEY_KEEP_READY, false));
        }
    }
    @Override protected void onPause() {
        // Return control of screen timeout to Android whenever app loses focus.
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        super.onPause();
    }
    @Override protected void onStop() {
        if (bound) {
            captions.setListener(null);
            unbindService(connection);
            bound = false;
            captions = null;
        }
        super.onStop();
    }

    private int dp(float x) { return Math.round(getResources().getDisplayMetrics().density * x); }
    private TextView text(String t, int sp, int color, boolean bold) {
        TextView v = new TextView(this);
        v.setText(t);
        v.setTextSize(sp);
        v.setTextColor(color);
        if (bold) v.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        return v;
    }
    private Button button(String label) {
        Button b = new Button(this);
        b.setText(label);
        b.setTextSize(15);
        b.setAllCaps(false);
        return b;
    }
    private LinearLayout.LayoutParams withTop(int top) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, -2);
        p.topMargin = dp(top);
        return p;
    }
    private void makeUi() {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(Color.rgb(14, 24, 39));
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding(dp(20), dp(19), dp(20), dp(20));
        scroll.addView(page);
        setContentView(scroll);

        page.addView(text("Live Chinese Offline", 27, Color.WHITE, true));
        page.addView(text("Mandarin streaming • No Google speech • No internet",
                12, Color.rgb(163, 188, 207), false), withTop(5));
        statusView = text("Loading model…", 15, Color.rgb(125, 211, 252), true);
        page.addView(statusView, withTop(19));
        metricView = text("Ready for offline recognition", 12, Color.rgb(170, 187, 205), false);
        page.addView(metricView, withTop(5));

        listenButton = button("Start listening");
        LinearLayout.LayoutParams startP = new LinearLayout.LayoutParams(-1, dp(55));
        startP.topMargin = dp(12);
        page.addView(listenButton, startP);
        listenButton.setEnabled(false);
        listenButton.setOnClickListener(v -> {
            if (captions == null) return;
            if (captions.isListening()) captions.stopListening();
            else beginListening();
        });
        TextView explainer = text(
                "Press Start to transcribe in this app, even if Android Go blocks overlays. " +
                "If floating captions are allowed, drag six dots to move the overlay, " +
                "or drop it at the bottom to stop.",
                12, Color.rgb(183, 199, 211), false);
        page.addView(explainer, withTop(9));
        page.addView(text("Screen stays on while this app is open. Normal screen timeout resumes when you switch apps or lock the phone.",
                12, Color.rgb(134, 239, 172), false), withTop(7));
        if (!Settings.canDrawOverlays(this)) {
            page.addView(text(
                    "Android Go blocks floating subtitles on this phone. " +
                    "You can still press Start listening and read or search the live transcript here. " +
                    "Keep this screen open to see text.",
                    13, Color.rgb(250, 206, 117), false), withTop(8));
        }


        // User opts in to an Android accessibility service designed solely for
        // speakerphone microphone sharing. Never auto-enable this system permission.
        SharedPreferences callPrefs = getSharedPreferences(CaptionService.PREFS, MODE_PRIVATE);
        callModeSwitch = new Switch(this);
        callModeSwitch.setText("Experimental Call Mode (speakerphone)");
        callModeSwitch.setTextColor(Color.WHITE);
        callModeSwitch.setTextSize(15);
        callModeSwitch.setChecked(callPrefs.getBoolean(CaptionService.KEY_CALL_MODE, false));
        page.addView(callModeSwitch, withTop(16));
        TextView callDescription = text(
                "For live captions of the other caller, turn on speakerphone. " +
                "Call Mode uses the microphone via an Accessibility Service; it cannot " +
                "read incoming call audio directly and may also hear your voice. " +
                "Nothing is uploaded. Captions remain visible during silence.",
                12, Color.rgb(196, 205, 221), false);
        page.addView(callDescription, withTop(5));
        callModeStatus = text("", 12, Color.rgb(250, 206, 117), true);
        page.addView(callModeStatus, withTop(8));
        Button accessibilitySettings = button("Enable Call Listening in Accessibility settings");
        page.addView(accessibilitySettings, withTop(5));
        accessibilitySettings.setOnClickListener(v -> openAccessibilitySettings());
        callModeSwitch.setOnCheckedChangeListener((v, enabled) -> {
            callPrefs.edit().putBoolean(CaptionService.KEY_CALL_MODE, enabled).apply();
            if (captions != null && captions.isListening()) {
                captions.stopListening();
                Toast.makeText(this, "Restart listening to change microphone mode",
                        Toast.LENGTH_LONG).show();
            }
            refreshCallModeStatus();
            if (enabled && !CallAudioAccessibilityService.isConnected()) {
                Toast.makeText(this,
                        "Enable Live Chinese Call Listening in Accessibility settings",
                        Toast.LENGTH_LONG).show();
                openAccessibilitySettings();
            }
        });
        refreshCallModeStatus();

        keepReadySwitch = new Switch(this);
        keepReadySwitch.setText("Keep model ready in background (extra battery/RAM)");
        keepReadySwitch.setTextColor(Color.WHITE);
        keepReadySwitch.setTextSize(15);
        keepReadySwitch.setChecked(callPrefs.getBoolean(CaptionService.KEY_KEEP_READY, false));
        page.addView(keepReadySwitch, withTop(15));
        page.addView(text(
                "Optional: keeps a persistent standby notification and the offline model in RAM. " +
                "Does not record audio or keep CPU awake while idle. " +
                "Use the notification's Stop standby button to turn it off. " +
                "Android may still close the process if it needs memory.",
                12, Color.rgb(196, 205, 221), false), withTop(4));
        keepReadySwitch.setOnCheckedChangeListener((v, enabled) -> {
            callPrefs.edit().putBoolean(CaptionService.KEY_KEEP_READY, enabled).apply();
            Intent standby = new Intent(this, ModelStandbyService.class);
            if (enabled) {
                standby.setAction(ModelStandbyService.ACTION_START);
                try {
                    startForegroundService(standby);
                } catch (RuntimeException e) {
                    callPrefs.edit().putBoolean(CaptionService.KEY_KEEP_READY, false).apply();
                    keepReadySwitch.setChecked(false);
                    Toast.makeText(this, "Standby service blocked by Android: " +
                            e.getMessage(), Toast.LENGTH_LONG).show();
                }
            } else {
                standby.setAction(ModelStandbyService.ACTION_STOP);
                startService(standby);
            }
        });

        LinearLayout settings = new LinearLayout(this);
        settings.setOrientation(LinearLayout.VERTICAL);
        settings.setPadding(dp(12), dp(11), dp(12), dp(11));
        settings.setBackgroundColor(Color.rgb(27, 42, 60));
        page.addView(settings, withTop(16));
        settings.addView(text("Floating subtitles — maximum size", 16, Color.WHITE, true));
        SharedPreferences p = getSharedPreferences(CaptionService.PREFS, MODE_PRIVATE);
        widthLabel = text("", 13, Color.WHITE, false);
        widthBar = addSlider(settings, widthLabel, 45, 96,
                p.getInt(CaptionService.KEY_WIDTH, 78), value -> {
                    p.edit().putInt(CaptionService.KEY_WIDTH, value).apply();
                    if (captions != null) captions.updateSettings();
                });
        linesLabel = text("", 13, Color.WHITE, false);
        linesBar = addSlider(settings, linesLabel, 1, 5,
                p.getInt(CaptionService.KEY_LINES, 5), value -> {
                    p.edit().putInt(CaptionService.KEY_LINES, value).apply();
                    if (captions != null) captions.updateSettings();
                });
        fontLabel = text("", 13, Color.WHITE, false);
        fontBar = addSlider(settings, fontLabel, 12, 40,
                Math.round(p.getFloat(CaptionService.KEY_FONT, 24f)), value -> {
                    p.edit().putFloat(CaptionService.KEY_FONT, (float) value).apply();
                    if (captions != null) captions.updateSettings();
                });
        refreshSettingsLabels();

        LinearLayout transcriptHeader = new LinearLayout(this);
        transcriptHeader.setOrientation(LinearLayout.HORIZONTAL);
        transcriptHeader.setGravity(Gravity.CENTER_VERTICAL);
        TextView transcriptTitle = text("Full transcript", 16, Color.WHITE, true);
        transcriptHeader.addView(transcriptTitle, new LinearLayout.LayoutParams(0, -2, 1f));
        Button expandTranscript = button("Open / Scroll");
        transcriptHeader.addView(expandTranscript,
                new LinearLayout.LayoutParams(dp(155), dp(50)));
        expandTranscript.setOnClickListener(v -> openFullTranscript());
        page.addView(transcriptHeader, withTop(15));
        LinearLayout searchRow = new LinearLayout(this);
        searchRow.setOrientation(LinearLayout.HORIZONTAL);
        page.addView(searchRow, withTop(8));
        findInput = new EditText(this);
        findInput.setSingleLine(true);
        findInput.setHint("Find words in transcript");
        findInput.setHintTextColor(Color.rgb(167, 182, 204));
        findInput.setTextColor(Color.WHITE);
        findInput.setTextSize(15);
        searchRow.addView(findInput, new LinearLayout.LayoutParams(0, dp(50), 1f));
        Button findButton = button("Find next");
        searchRow.addView(findButton, new LinearLayout.LayoutParams(dp(118), dp(50)));
        findButton.setOnClickListener(v -> findNext());
        findCount = text("Search completed and live subtitles", 12,
                Color.rgb(175, 202, 229), false);
        page.addView(findCount, withTop(4));

        transcriptScroll = new ScrollView(this);
        transcriptScroll.setBackgroundColor(Color.rgb(25, 39, 58));
        // Parent page ScrollView must not steal drags meant for transcript history.
        transcriptScroll.setOnTouchListener((v, e) -> {
            if (e.getActionMasked() == MotionEvent.ACTION_DOWN)
                v.getParent().requestDisallowInterceptTouchEvent(true);
            if (e.getActionMasked() == MotionEvent.ACTION_UP ||
                    e.getActionMasked() == MotionEvent.ACTION_CANCEL)
                v.getParent().requestDisallowInterceptTouchEvent(false);
            return false;
        });
        transcriptView = text("Tap Start listening. Transcription will also appear in the floating box.",
                24, Color.rgb(229, 236, 245), false);
        transcriptView.setTextIsSelectable(true);
        transcriptView.setPadding(dp(13), dp(13), dp(13), dp(13));
        transcriptScroll.addView(transcriptView);
        LinearLayout.LayoutParams transcriptP = new LinearLayout.LayoutParams(-1, dp(215));
        transcriptP.topMargin = dp(8);
        page.addView(transcriptScroll, transcriptP);

        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        page.addView(actions, withTop(9));
        Button copy = button("Copy");
        Button save = button("Save .txt");
        Button clear = button("Clear");
        actions.addView(copy, new LinearLayout.LayoutParams(0, dp(54), 1f));
        actions.addView(save, new LinearLayout.LayoutParams(0, dp(54), 1.2f));
        actions.addView(clear, new LinearLayout.LayoutParams(0, dp(54), 1f));
        copy.setOnClickListener(v -> {
            ClipboardManager manager = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
            manager.setPrimaryClip(ClipData.newPlainText("Chinese transcript",
                    captions != null ? captions.getTranscript() : ""));
            Toast.makeText(this, "Copied", Toast.LENGTH_SHORT).show();
        });
        save.setOnClickListener(v -> {
            Intent i = new Intent(Intent.ACTION_CREATE_DOCUMENT);
            i.addCategory(Intent.CATEGORY_OPENABLE);
            i.setType("text/plain");
            i.putExtra(Intent.EXTRA_TITLE, "live-chinese-transcript.txt");
            startActivityForResult(i, SAVE_DOCUMENT);
        });
        clear.setOnClickListener(v -> {
            if (captions != null) captions.clear();
        });
        TextView foot = text("Mic recording uses a foreground service. " +
                "Android may temporarily hide overlays on secure system screens.",
                11, Color.rgb(148, 163, 184), false);
        foot.setGravity(Gravity.CENTER);
        page.addView(foot, withTop(12));
    }
    private interface OnValue { void changed(int value); }
    private SeekBar addSlider(LinearLayout parent, TextView label, int min, int max,
                              int current, OnValue callback) {
        parent.addView(label, withTop(8));
        SeekBar seek = new SeekBar(this);
        seek.setMin(min);
        seek.setMax(max);
        seek.setProgress(Math.max(min, Math.min(max, current)));
        parent.addView(seek);
        seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar s, int progress, boolean fromUser) {
                if (!updatingSliders && fromUser) {
                    callback.changed(progress);
                    refreshSettingsLabels();
                }
            }
            @Override public void onStartTrackingTouch(SeekBar s) {}
            @Override public void onStopTrackingTouch(SeekBar s) {}
        });
        return seek;
    }
    private void openAccessibilitySettings() {
        Intent i = new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS);
        startActivity(i);
    }
    private void refreshCallModeStatus() {
        if (callModeStatus == null) return;
        boolean enabled = getSharedPreferences(CaptionService.PREFS, MODE_PRIVATE)
                .getBoolean(CaptionService.KEY_CALL_MODE, false);
        callModeStatus.setText(!enabled ? "Call Mode OFF — normal microphone"
                : CallAudioAccessibilityService.isConnected()
                        ? "Call Mode READY — enable speakerphone during your call"
                        : "Accessibility permission needed — tap Enable above");
        callModeStatus.setTextColor(CallAudioAccessibilityService.isConnected()
                ? Color.rgb(134, 239, 172) : Color.rgb(250, 206, 117));
    }
    private void refreshSettingsLabels() {
        SharedPreferences p = getSharedPreferences(CaptionService.PREFS, MODE_PRIVATE);
        widthLabel.setText("Maximum width: " + p.getInt(CaptionService.KEY_WIDTH, 78) + "% of screen");
        linesLabel.setText("Maximum visible lines: " + p.getInt(CaptionService.KEY_LINES, 5) + " (hard limit 5)");
        fontLabel.setText("Subtitle font: " +
                Math.round(p.getFloat(CaptionService.KEY_FONT, 24f)) + " sp (or pinch in overlay)");
    }
    @Override public void onChanged() {
        if (captions == null) return;
        runOnUiThread(() -> {
            if (captions == null) return;
            statusView.setText(captions.getStatus());
            refreshCallModeStatus();
            metricView.setText(captions.getMetric());
            listenButton.setEnabled(captions.isReady());
            listenButton.setText(captions.isListening() ? "Stop listening" : "Start listening");
            String current = captions.getTranscript();
            renderTranscript(current);
            refreshFullTranscript(current);
            SharedPreferences p = getSharedPreferences(CaptionService.PREFS, MODE_PRIVATE);
            if (fontBar != null) {
                updatingSliders = true;
                fontBar.setProgress(Math.round(p.getFloat(CaptionService.KEY_FONT, 24f)));
                updatingSliders = false;
                refreshSettingsLabels();
            }
        });
    }
    private void openFullTranscript() {
        if (fullTranscriptDialog != null && fullTranscriptDialog.isShowing()) return;
        Dialog dialog = new Dialog(this);
        dialog.getWindow(); // Created before show; window size is configured below.
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(12), dp(10), dp(12), dp(12));
        root.setBackgroundColor(Color.rgb(14, 24, 39));
        LinearLayout bar = new LinearLayout(this);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.addView(text("Full transcript · live", 19, Color.WHITE, true),
                new LinearLayout.LayoutParams(0, -2, 1f));
        Button done = button("Close");
        bar.addView(done, new LinearLayout.LayoutParams(dp(90), dp(48)));
        done.setOnClickListener(v -> dialog.dismiss());
        root.addView(bar);
        LinearLayout findBar = new LinearLayout(this);
        fullFindInput = new EditText(this);
        fullFindInput.setSingleLine(true);
        fullFindInput.setHint("Find a word or phrase");
        fullFindInput.setTextColor(Color.WHITE);
        fullFindInput.setHintTextColor(Color.LTGRAY);
        fullFindInput.setTextSize(15);
        findBar.addView(fullFindInput, new LinearLayout.LayoutParams(0, dp(52), 1f));
        Button next = button("Find next");
        findBar.addView(next, new LinearLayout.LayoutParams(dp(112), dp(52)));
        next.setOnClickListener(v -> findInFullTranscript());
        root.addView(findBar);
        fullFindCount = text("Swipe up or down to read all previous lines", 12,
                Color.rgb(170, 202, 229), false);
        root.addView(fullFindCount);
        fullTranscriptScroll = new ScrollView(this);
        fullTranscriptScroll.setFillViewport(true);
        fullTranscriptScroll.setBackgroundColor(Color.rgb(25, 39, 58));
        fullTranscriptView = text("", 22, Color.rgb(229, 236, 245), false);
        fullTranscriptView.setTextIsSelectable(false);
        fullTranscriptView.setPadding(dp(10), dp(12), dp(10), dp(12));
        fullTranscriptScroll.addView(fullTranscriptView);
        root.addView(fullTranscriptScroll, new LinearLayout.LayoutParams(-1, 0, 1f));
        dialog.setContentView(root);
        dialog.setOnDismissListener(v -> {
            fullTranscriptDialog = null;
            fullTranscriptScroll = null;
            fullTranscriptView = null;
            fullFindInput = null;
            fullFindCount = null;
        });
        fullTranscriptDialog = dialog;
        fullMatchIndex = -1;
        fullLastQuery = "";
        lastFullText = "__refresh__";
        dialog.show();
        if (dialog.getWindow() != null) {
            // Dialog has its own window, and should not timeout while reading.
            dialog.getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            dialog.getWindow().setLayout(
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.MATCH_PARENT);
        }
        refreshFullTranscript(captions == null ? lastRenderedTranscript : captions.getTranscript());
    }
    private void refreshFullTranscript(String current) {
        if (fullTranscriptView == null || fullTranscriptScroll == null ||
                current.equals(lastFullText)) return;
        boolean followLatest = fullTranscriptScroll.getChildCount() == 0 ||
                fullTranscriptScroll.getScrollY() + fullTranscriptScroll.getHeight() >=
                fullTranscriptScroll.getChildAt(0).getHeight() - dp(45);
        lastFullText = current;
        SpannableString contents = new SpannableString(
                current.isEmpty() ? "Waiting for speech…" : current);
        if (fullMatchIndex >= 0 && !fullLastQuery.isEmpty() &&
                fullMatchIndex + fullLastQuery.length() <= current.length() &&
                current.regionMatches(true, fullMatchIndex,
                        fullLastQuery, 0, fullLastQuery.length())) {
            contents.setSpan(new BackgroundColorSpan(Color.YELLOW),
                    fullMatchIndex, fullMatchIndex + fullLastQuery.length(),
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            contents.setSpan(new ForegroundColorSpan(Color.BLACK),
                    fullMatchIndex, fullMatchIndex + fullLastQuery.length(),
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        fullTranscriptView.setText(contents);
        if (followLatest && fullMatchIndex < 0) {
            fullTranscriptScroll.post(() -> {
                if (fullTranscriptScroll != null)
                    fullTranscriptScroll.fullScroll(View.FOCUS_DOWN);
            });
        }
    }
    private void findInFullTranscript() {
        if (fullFindInput == null || fullTranscriptView == null) return;
        String query = fullFindInput.getText().toString().trim();
        String transcript = captions != null ? captions.getTranscript() : lastRenderedTranscript;
        if (query.isEmpty()) {
            fullLastQuery = "";
            fullMatchIndex = -1;
            fullFindCount.setText("Enter a word or phrase");
            redrawFullTranscript(transcript);
            return;
        }
        String lower = transcript.toLowerCase(Locale.ROOT);
        String needle = query.toLowerCase(Locale.ROOT);
        if (!query.equalsIgnoreCase(fullLastQuery)) fullMatchIndex = -1;
        fullLastQuery = query;
        int start = lower.indexOf(needle, Math.max(0, fullMatchIndex + 1));
        if (start < 0) start = lower.indexOf(needle);
        fullMatchIndex = start;
        if (start < 0) {
            fullFindCount.setText("No matches found");
        } else {
            int count = 0;
            for (int p = 0; (p = lower.indexOf(needle, p)) >= 0; p += needle.length()) count++;
            fullFindCount.setText(count + " matches · Find next to continue");
        }
        redrawFullTranscript(transcript);
        final int offset = start;
        if (offset >= 0) fullTranscriptView.post(() -> {
            if (fullTranscriptView != null && fullTranscriptView.getLayout() != null &&
                    fullTranscriptScroll != null) {
                int line = fullTranscriptView.getLayout().getLineForOffset(
                        Math.min(offset, fullTranscriptView.length()));
                fullTranscriptScroll.smoothScrollTo(0,
                        Math.max(0, fullTranscriptView.getLayout().getLineTop(line) - dp(30)));
            }
        });
    }
    private void redrawFullTranscript(String transcript) {
        lastFullText = "__refresh__";
        refreshFullTranscript(transcript);
    }
    private void renderTranscript(String current) {
        if (transcriptView == null || current.equals(lastRenderedTranscript)) return;
        boolean atBottom = transcriptScroll == null || transcriptScroll.getChildCount() == 0 ||
                transcriptScroll.getScrollY() + transcriptScroll.getHeight() >=
                transcriptScroll.getChildAt(0).getHeight() - dp(35);
        lastRenderedTranscript = current;
        String shown = current.isEmpty() ? "Waiting for speech…" : current;
        SpannableString rich = new SpannableString(shown);
        if (!lastFindTerm.isEmpty() && currentMatch >= 0 &&
                currentMatch + lastFindTerm.length() <= current.length() &&
                current.regionMatches(true, currentMatch, lastFindTerm, 0, lastFindTerm.length())) {
            rich.setSpan(new BackgroundColorSpan(Color.YELLOW), currentMatch,
                    currentMatch + lastFindTerm.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            rich.setSpan(new ForegroundColorSpan(Color.BLACK), currentMatch,
                    currentMatch + lastFindTerm.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        transcriptView.setText(rich);
        if (atBottom && currentMatch < 0 && transcriptScroll != null) {
            transcriptScroll.post(() -> transcriptScroll.fullScroll(View.FOCUS_DOWN));
        }
    }
    private void findNext() {
        if (findInput == null || transcriptView == null) return;
        String query = findInput.getText().toString().trim();
        if (query.isEmpty()) {
            findCount.setText("Enter a word or phrase to search");
            lastFindTerm = "";
            currentMatch = -1;
            renderHighlightedMatch();
            return;
        }
        String full = captions != null ? captions.getTranscript() : lastRenderedTranscript;
        String lower = full.toLowerCase(Locale.ROOT);
        String needle = query.toLowerCase(Locale.ROOT);
        if (!query.equalsIgnoreCase(lastFindTerm)) {
            currentMatch = -1;
        }
        lastFindTerm = query;
        int next = lower.indexOf(needle, Math.max(0, currentMatch + 1));
        if (next < 0) next = lower.indexOf(needle);
        if (next < 0) {
            currentMatch = -1;
            findCount.setText("No matches for: " + query);
            renderHighlightedMatch();
            return;
        }
        currentMatch = next;
        int count = 0;
        int position = 0;
        while ((position = lower.indexOf(needle, position)) >= 0) {
            count++;
            position += needle.length();
        }
        findCount.setText(count + " matches · Find next to continue");
        renderHighlightedMatch();
        final int offset = currentMatch;
        transcriptView.post(() -> {
            if (transcriptView.getLayout() != null && transcriptScroll != null) {
                int safe = Math.min(offset, transcriptView.length());
                int line = transcriptView.getLayout().getLineForOffset(safe);
                int y = transcriptView.getLayout().getLineTop(line);
                transcriptScroll.smoothScrollTo(0, Math.max(0, y - dp(30)));
            }
        });
    }
    private void renderHighlightedMatch() {
        // Force a redraw of the highlight without discarding the transcript.
        String current = lastRenderedTranscript;
        lastRenderedTranscript = "__redraw_transcript__";
        renderTranscript(current);
    }
    private void beginListening() {
        if (captions == null || !captions.isReady()) return;
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, MIC_PERMISSION);
            return;
        }
        // Android Go may permanently deny SYSTEM_ALERT_WINDOW. Never block
        // microphone recording or in-app live transcription because of this.
        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this,
                    "Floating captions are blocked by Android Go; in-app live transcript will work",
                    Toast.LENGTH_LONG).show();
        }
        if (getSharedPreferences(CaptionService.PREFS, MODE_PRIVATE)
                .getBoolean(CaptionService.KEY_CALL_MODE, false)
                && !CallAudioAccessibilityService.isConnected()) {
            Toast.makeText(this, "Enable the Call Listening accessibility service first",
                    Toast.LENGTH_LONG).show();
            openAccessibilitySettings();
            return;
        }
        Intent i = new Intent(this, CaptionService.class);
        i.setAction(CaptionService.ACTION_START);
        startForegroundService(i);
    }
    @Override public void onRequestPermissionsResult(int req, String[] permissions, int[] result) {
        super.onRequestPermissionsResult(req, permissions, result);
        if (req == MIC_PERMISSION && result.length > 0 &&
                result[0] == PackageManager.PERMISSION_GRANTED) beginListening();
    }
    @Override protected void onActivityResult(int req, int result, Intent data) {
        super.onActivityResult(req, result, data);
        if (req != SAVE_DOCUMENT || result != RESULT_OK || data == null) return;
        try (OutputStream stream = getContentResolver().openOutputStream(data.getData())) {
            if (stream != null) {
                String t = captions != null ? captions.getTranscript() : "";
                stream.write(t.getBytes(StandardCharsets.UTF_8));
                Toast.makeText(this, "Transcript saved", Toast.LENGTH_SHORT).show();
            }
        } catch (Exception e) {
            Toast.makeText(this, "Save failed: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }
}
