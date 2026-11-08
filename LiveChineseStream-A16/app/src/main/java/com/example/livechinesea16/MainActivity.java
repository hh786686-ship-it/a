package com.example.livechinesea16;

import android.Manifest;
import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Single-model Mandarin / English transcription with Qwen3-ASR 0.6B INT8.
 * Audio arrives continuously but transcription updates after completed chunks,
 * not word-by-word. No Paraformer, SenseVoice, or neural punctuation model.
 */
public final class MainActivity extends Activity {
    private static final String TAG = "LiveChineseA16";
    private static final int SAMPLE_RATE = 16000;
    private static final int PERMISSION_REQUEST = 42;
    private static final int SAVE_REQUEST = 43;
    private static final int CHUNK_SAMPLES = SAMPLE_RATE * 2;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final Object textLock = new Object();
    private final AtomicBoolean active = new AtomicBoolean(false);
    private final ArrayBlockingQueue<float[]> audioQueue = new ArrayBlockingQueue<>(3);
    private volatile boolean modelReady = false;
    private volatile boolean modelFailed = false;
    private QwenCorrectionEngine qwen;
    private AudioRecord audioRecord;
    private Thread captureThread;
    private Thread decoderThread;
    private TextView transcription;
    private TextView status;
    private TextView indicator;
    private Button microphoneButton;
    private ScrollView scroller;
    private final StringBuilder completed = new StringBuilder();
    private long sessionNumber = 0;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().setStatusBarColor(Color.rgb(14, 24, 39));
        getWindow().setNavigationBarColor(Color.rgb(14, 24, 39));
        setupUi();
        setStatus("Loading offline Qwen3-ASR 0.6B…");
        microphoneButton.setEnabled(false);
        new Thread(this::loadModel, "qwen-loader").start();
    }

    private int dp(float value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private TextView label(String value, int size, int color, boolean bold) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(size);
        view.setTextColor(color);
        if (bold) view.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        return view;
    }

    private Button button(String value) {
        Button button = new Button(this);
        button.setText(value);
        button.setTextSize(15);
        button.setAllCaps(false);
        return button;
    }

    private void setupUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(20), dp(24), dp(20), dp(16));
        root.setBackgroundColor(Color.rgb(14, 24, 39));
        setContentView(root);

        root.addView(label("Live Chinese", 29, Color.WHITE, true));
        TextView subtitle = label("Offline • Qwen3-ASR 0.6B only • Mandarin / English",
                13, Color.rgb(167, 187, 207), false);
        LinearLayout.LayoutParams subtitleParams = new LinearLayout.LayoutParams(-1, -2);
        subtitleParams.topMargin = dp(5);
        root.addView(subtitle, subtitleParams);

        status = label("Preparing…", 15, Color.rgb(125, 211, 252), true);
        LinearLayout.LayoutParams statusParams = new LinearLayout.LayoutParams(-1, -2);
        statusParams.topMargin = dp(24);
        root.addView(status, statusParams);

        indicator = label("2-second audio chunks • Timing depends on phone", 12,
                Color.rgb(167, 187, 207), false);
        LinearLayout.LayoutParams indicatorParams = new LinearLayout.LayoutParams(-1, -2);
        indicatorParams.topMargin = dp(6);
        root.addView(indicator, indicatorParams);

        microphoneButton = button("Start listening");
        LinearLayout.LayoutParams listenParams = new LinearLayout.LayoutParams(-1, dp(60));
        listenParams.topMargin = dp(16);
        root.addView(microphoneButton, listenParams);
        microphoneButton.setOnClickListener(v -> {
            if (active.get()) stopListening(); else startListening();
        });

        TextView heading = label("Transcript", 16, Color.WHITE, true);
        LinearLayout.LayoutParams headingParams = new LinearLayout.LayoutParams(-1, -2);
        headingParams.topMargin = dp(22);
        headingParams.bottomMargin = dp(9);
        root.addView(heading, headingParams);

        scroller = new ScrollView(this);
        scroller.setFillViewport(true);
        scroller.setBackgroundColor(Color.rgb(25, 39, 58));
        transcription = label("Tap Start listening. Qwen transcribes short audio chunks; words do not appear instantly.",
                24, Color.rgb(229, 236, 245), false);
        transcription.setPadding(dp(15), dp(16), dp(15), dp(16));
        transcription.setTextIsSelectable(true);
        scroller.addView(transcription);
        root.addView(scroller, new LinearLayout.LayoutParams(-1, 0, 1f));

        LinearLayout actions = new LinearLayout(this);
        LinearLayout.LayoutParams actionsParams = new LinearLayout.LayoutParams(-1, -2);
        actionsParams.topMargin = dp(12);
        root.addView(actions, actionsParams);
        Button copy = button("Copy");
        Button save = button("Save .txt");
        Button clear = button("Clear");
        actions.addView(copy, new LinearLayout.LayoutParams(0, dp(56), 1f));
        actions.addView(save, new LinearLayout.LayoutParams(0, dp(56), 1.25f));
        actions.addView(clear, new LinearLayout.LayoutParams(0, dp(56), 1f));

        copy.setOnClickListener(v -> {
            ClipboardManager clipboard = (ClipboardManager)getSystemService(CLIPBOARD_SERVICE);
            clipboard.setPrimaryClip(ClipData.newPlainText("Chinese transcript", transcript()));
            toast("Copied");
        });
        save.setOnClickListener(v -> {
            Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            intent.setType("text/plain");
            intent.putExtra(Intent.EXTRA_TITLE, "live-chinese-transcript.txt");
            startActivityForResult(intent, SAVE_REQUEST);
        });
        clear.setOnClickListener(v -> {
            synchronized (textLock) { completed.setLength(0); }
            draw();
        });
        TextView footer = label("No Google speech service • No internet permission",
                11, Color.rgb(148, 163, 184), false);
        footer.setGravity(Gravity.CENTER_HORIZONTAL);
        LinearLayout.LayoutParams footerParams = new LinearLayout.LayoutParams(-1, -2);
        footerParams.topMargin = dp(12);
        root.addView(footer, footerParams);
    }

    private void loadModel() {
        try {
            qwen = new QwenCorrectionEngine(this);
            modelReady = true;
            modelFailed = false;
            Log.i(TAG, "Qwen3-ASR 0.6B single model loaded");
            ui.post(() -> {
                setStatus("Qwen model ready");
                microphoneButton.setEnabled(true);
                indicator.setText("Qwen only • 2-second audio chunks • ，。");
            });
            if (getIntent().getBooleanExtra("ciSmokeTest", false)) {
                Thread test = new Thread(this::runSelfTest, "qwen-self-test");
                test.start();
            }
        } catch (Throwable error) {
            modelFailed = true;
            Log.e(TAG, "Qwen model could not load", error);
            ui.post(() -> {
                setStatus("Qwen cannot load on this device");
                indicator.setText(error.getClass().getSimpleName() + ": " + error.getMessage());
                microphoneButton.setEnabled(false);
            });
        }
    }

    private void runSelfTest() {
        try {
            long start = SystemClock.elapsedRealtime();
            String result = qwen.recognize(new float[SAMPLE_RATE * 2]);
            long took = SystemClock.elapsedRealtime() - start;
            Log.i(TAG, "Qwen-only native inference passed: " + took + " ms; output=" + result);
            ui.post(() -> setStatus("Qwen self-test passed • Ready"));
        } catch (Throwable error) {
            Log.e(TAG, "Qwen-only inference failed", error);
            ui.post(() -> setStatus("Qwen inference failed: " + error.getClass().getSimpleName()));
        }
    }

    private void startListening() {
        if (!modelReady || active.get()) return;
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[] {Manifest.permission.RECORD_AUDIO}, PERMISSION_REQUEST);
            return;
        }
        int min = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT);
        if (min <= 0) { setStatus("16 kHz microphone unavailable"); return; }
        try {
            audioRecord = new AudioRecord(MediaRecorder.AudioSource.MIC, SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
                    Math.max(min * 2, SAMPLE_RATE * 2 * 2));
            if (audioRecord.getState() != AudioRecord.STATE_INITIALIZED) {
                audioRecord.release();
                setStatus("Microphone could not start");
                return;
            }
            audioQueue.clear();
            audioRecord.startRecording();
            Log.i(TAG, "AudioRecord state after start: " + audioRecord.getRecordingState());
            active.set(true);
            sessionNumber++;
            final long session = sessionNumber;
            microphoneButton.setText("Stop and finish");
            setStatus("● Listening • transcribing short chunks");
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            decoderThread = new Thread(() -> decodeLoop(session), "qwen-decode");
            captureThread = new Thread(this::captureLoop, "qwen-capture");
            decoderThread.start();
            captureThread.start();
        } catch (Throwable e) {
            Log.e(TAG, "Microphone startup exception", e);
            setStatus("Microphone error: " + e.getClass().getSimpleName());
        }
    }

    private void stopListening() {
        active.set(false);
        microphoneButton.setText("Start listening");
        setStatus("Finishing recorded audio…");
    }

    private void captureLoop() {
        short[] pcm = new short[1600];
        float[] chunk = new float[CHUNK_SAMPLES];
        int used = 0;
        try {
            while (active.get()) {
                int n = audioRecord.read(pcm, 0, pcm.length);
                if (n == AudioRecord.ERROR_DEAD_OBJECT) break;
                if (n <= 0) continue;
                for (int i = 0; i < n; i++) {
                    chunk[used++] = pcm[i] / 32768.0f;
                    if (used == CHUNK_SAMPLES) {
                        enqueue(chunk);
                        chunk = new float[CHUNK_SAMPLES];
                        used = 0;
                    }
                }
            }
            if (used >= SAMPLE_RATE / 4) {
                float[] tail = new float[used];
                System.arraycopy(chunk, 0, tail, 0, used);
                enqueue(tail);
            }
        } catch (Throwable error) {
            Log.e(TAG, "Microphone read failed", error);
            ui.post(() -> setStatus("Microphone read error"));
        } finally {
            active.set(false);
            try { audioRecord.stop(); } catch (Throwable ignored) {}
            try { audioRecord.release(); } catch (Throwable ignored) {}
        }
    }

    private void enqueue(float[] chunk) {
        if (!audioQueue.offer(chunk)) {
            // Backpressure is visible rather than silently losing recorded speech.
            Log.w(TAG, "Qwen decode queue saturated; transcription falling behind");
            ui.post(() -> indicator.setText("Phone is slower than recording • audio queue full"));
            try { audioQueue.put(chunk); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        }
    }

    private void decodeLoop(long session) {
        try {
            while (active.get() || !audioQueue.isEmpty()
                    || (captureThread != null && captureThread.isAlive())) {
                float[] speech = audioQueue.poll(300, java.util.concurrent.TimeUnit.MILLISECONDS);
                if (speech == null) continue;
                long started = SystemClock.elapsedRealtime();
                String result = qwen.recognize(speech);
                long elapsed = SystemClock.elapsedRealtime() - started;
                Log.i(TAG, "Qwen-only chunk decoded: " + elapsed + "ms / "
                        + (speech.length / (float)SAMPLE_RATE) + "s audio");
                if (result != null && !result.trim().isEmpty()) {
                    synchronized (textLock) {
                        if (session == sessionNumber) {
                            if (completed.length() > 0) completed.append("\n");
                            completed.append(endPunctuation(result.trim()));
                        }
                    }
                    ui.post(this::draw);
                }
                ui.post(() -> indicator.setText("Last chunk processing: " + elapsed
                        + " ms (not end-to-end delay)"));
            }
        } catch (Throwable error) {
            Log.e(TAG, "Qwen transcription failed", error);
            ui.post(() -> setStatus("Qwen transcription failed: " + error.getClass().getSimpleName()));
        } finally {
            ui.post(() -> {
                microphoneButton.setText("Start listening");
                setStatus("Ready • Transcript retained");
                getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            });
        }
    }

    private String endPunctuation(String text) {
        String s = text.trim().replaceAll("\\s+", " ")
                .replaceAll("(?<=[\\p{IsHan}]) +(?=[\\p{IsHan}])", "");
        if (s.isEmpty() || s.matches(".*[。！？.!?]$")) return s;
        return s.codePoints().anyMatch(cp ->
                Character.UnicodeScript.of(cp) == Character.UnicodeScript.HAN) ? s + "。" : s + ".";
    }

    private String transcript() {
        synchronized (textLock) { return completed.toString(); }
    }

    private void draw() {
        String text = transcript();
        transcription.setText(text.isEmpty() ? "Listening for speech…" : text);
        scroller.post(() -> scroller.fullScroll(View.FOCUS_DOWN));
    }

    private void setStatus(String value) { status.setText(value); }
    private void toast(String msg) { Toast.makeText(this, msg, Toast.LENGTH_SHORT).show(); }

    @Override public void onRequestPermissionsResult(int request, String[] p, int[] granted) {
        super.onRequestPermissionsResult(request, p, granted);
        if (request == PERMISSION_REQUEST) {
            if (granted.length > 0 && granted[0] == PackageManager.PERMISSION_GRANTED) startListening();
            else toast("Microphone permission is required");
        }
    }

    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request != SAVE_REQUEST || result != RESULT_OK || data == null) return;
        try (OutputStream out = getContentResolver().openOutputStream(data.getData())) {
            if (out == null) throw new IllegalStateException("Could not write output");
            out.write(transcript().getBytes(StandardCharsets.UTF_8));
            toast("Saved");
        } catch (Exception e) { toast("Save failed: " + e.getMessage()); }
    }

    @Override protected void onDestroy() {
        active.set(false);
        super.onDestroy();
    }
}
