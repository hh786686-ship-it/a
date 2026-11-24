package com.example.livechinesea16;

import android.Manifest;
import android.app.Activity;
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
import android.widget.Button;
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
    private Button listenButton;
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
                "Start inside this app. Captions stay over Home and other apps, even during silence. " +
                "Drag the six dots to move; drop at the very bottom to close. Pinch subtitle text to zoom.",
                12, Color.rgb(183, 199, 211), false);
        page.addView(explainer, withTop(9));

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

        page.addView(text("Full transcript", 16, Color.WHITE, true), withTop(15));
        ScrollView transcriptScroll = new ScrollView(this);
        transcriptScroll.setBackgroundColor(Color.rgb(25, 39, 58));
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
            metricView.setText(captions.getMetric());
            listenButton.setEnabled(captions.isReady());
            listenButton.setText(captions.isListening() ? "Stop listening" : "Start listening");
            String current = captions.getTranscript();
            transcriptView.setText(current.isEmpty() ? "Waiting for speech…" : current);
            SharedPreferences p = getSharedPreferences(CaptionService.PREFS, MODE_PRIVATE);
            if (fontBar != null) {
                updatingSliders = true;
                fontBar.setProgress(Math.round(p.getFloat(CaptionService.KEY_FONT, 24f)));
                updatingSliders = false;
                refreshSettingsLabels();
            }
        });
    }
    private void beginListening() {
        if (captions == null || !captions.isReady()) return;
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, MIC_PERMISSION);
            return;
        }
        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "Allow display over other apps, then return and press Start",
                    Toast.LENGTH_LONG).show();
            Intent i = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:" + getPackageName()));
            startActivity(i);
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
