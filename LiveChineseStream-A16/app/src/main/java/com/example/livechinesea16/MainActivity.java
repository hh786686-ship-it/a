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

import com.k2fsa.sherpa.onnx.EndpointConfig;
import com.k2fsa.sherpa.onnx.EndpointRule;
import com.k2fsa.sherpa.onnx.FeatureConfig;
import com.k2fsa.sherpa.onnx.OnlineModelConfig;
import com.k2fsa.sherpa.onnx.OnlineParaformerModelConfig;
import com.k2fsa.sherpa.onnx.OnlineRecognizer;
import com.k2fsa.sherpa.onnx.OnlineRecognizerConfig;
import com.k2fsa.sherpa.onnx.OnlineStream;

import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;


public final class MainActivity extends Activity {
    private static final int SAMPLE_RATE = 16000;
    private static final String LOG_TAG = "LiveChineseA16";
    private static final int AUDIO_PERMISSION = 42;
    private static final int SAVE_DOCUMENT = 43;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final Object transcriptLock = new Object();
    private volatile boolean recording = false;
    private volatile boolean busy = false;
    private volatile boolean modelReady = false;
    private OnlineRecognizer recognizer;
    private AudioRecord microphone;
    private TextView transcriptView;
    private TextView stateView;
    private TextView metricView;
    private Button recordButton;
    private ScrollView transcriptScroller;
    private final ArrayList<TranscriptSegment> committedSegments = new ArrayList<>();
    private final ExecutorService correctionExecutor = Executors.newSingleThreadExecutor();
    private volatile AccuracyEngine accuracyEngine;
    private int transcriptGeneration = 0;
    private String partial = "";
    private long lastUiMs = 0L;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().setStatusBarColor(Color.rgb(14, 24, 39));
        getWindow().setNavigationBarColor(Color.rgb(14, 24, 39));
        makeUi();
        setStatus("Loading offline Chinese speech model…");
        recordButton.setEnabled(false);
        new Thread(this::loadModel, "model-loader").start();
    }

    private int dp(float v) { return Math.round(v * getResources().getDisplayMetrics().density); }

    private TextView label(String value, int size, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setText(value);
        t.setTextSize(size);
        t.setTextColor(color);
        if (bold) t.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        return t;
    }

    private Button button(String name) {
        Button b = new Button(this);
        b.setText(name);
        b.setAllCaps(false);
        b.setTextSize(15);
        return b;
    }

    private void makeUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.rgb(14, 24, 39));
        root.setPadding(dp(20), dp(24), dp(20), dp(16));
        setContentView(root);

        TextView title = label("Live Chinese", 29, Color.WHITE, true);
        root.addView(title);
        TextView sub = label("100% offline • Mandarin / English", 13,
                Color.rgb(167, 187, 207), false);
        LinearLayout.LayoutParams subP = new LinearLayout.LayoutParams(-1, -2);
        subP.topMargin = dp(5);
        root.addView(sub, subP);

        stateView = label("Preparing…", 15, Color.rgb(125, 211, 252), true);
        LinearLayout.LayoutParams stateP = new LinearLayout.LayoutParams(-1, -2);
        stateP.topMargin = dp(26);
        root.addView(stateView, stateP);
        metricView = label("Streaming partial text as you speak", 12,
                Color.rgb(167, 187, 207), false);
        LinearLayout.LayoutParams metricP = new LinearLayout.LayoutParams(-1, -2);
        metricP.topMargin = dp(6);
        root.addView(metricView, metricP);

        recordButton = button("Start listening");
        LinearLayout.LayoutParams bP = new LinearLayout.LayoutParams(-1, dp(60));
        bP.topMargin = dp(17);
        root.addView(recordButton, bP);
        recordButton.setOnClickListener(v -> {
            if (recording) {
                stopRecording();
            } else {
                startRecording();
            }
        });

        TextView heading = label("Transcript", 16, Color.WHITE, true);
        LinearLayout.LayoutParams hP = new LinearLayout.LayoutParams(-1, -2);
        hP.topMargin = dp(22);
        hP.bottomMargin = dp(9);
        root.addView(heading, hP);

        transcriptScroller = new ScrollView(this);
        transcriptScroller.setFillViewport(true);
        transcriptScroller.setBackgroundColor(Color.rgb(25, 39, 58));
        transcriptView = label("Tap Start listening and begin speaking. Text will appear before you finish your sentence.",
                21, Color.rgb(229, 236, 245), false);
        transcriptView.setPadding(dp(15), dp(16), dp(15), dp(16));
        transcriptView.setTextIsSelectable(true);
        transcriptScroller.addView(transcriptView);
        root.addView(transcriptScroller, new LinearLayout.LayoutParams(-1, 0, 1f));

        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams aP = new LinearLayout.LayoutParams(-1, -2);
        aP.topMargin = dp(12);
        root.addView(actions, aP);
        Button copy = button("Copy");
        Button save = button("Save .txt");
        Button clear = button("Clear");
        actions.addView(copy, new LinearLayout.LayoutParams(0, dp(56), 1f));
        actions.addView(save, new LinearLayout.LayoutParams(0, dp(56), 1.25f));
        actions.addView(clear, new LinearLayout.LayoutParams(0, dp(56), 1f));
        copy.setOnClickListener(v -> {
            String text = transcript();
            ClipboardManager cb = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
            cb.setPrimaryClip(ClipData.newPlainText("Chinese transcript", text));
            toast("Copied");
        });
        save.setOnClickListener(v -> {
            Intent i = new Intent(Intent.ACTION_CREATE_DOCUMENT);
            i.addCategory(Intent.CATEGORY_OPENABLE);
            i.setType("text/plain");
            i.putExtra(Intent.EXTRA_TITLE, "live-chinese-transcript.txt");
            startActivityForResult(i, SAVE_DOCUMENT);
        });
        clear.setOnClickListener(v -> {
            synchronized (transcriptLock) { committedSegments.clear(); partial = ""; transcriptGeneration++; }
            drawTranscript();
        });

        TextView footer = label("No Google speech service • No internet permission • Runs on device",
                11, Color.rgb(148, 163, 184), false);
        footer.setGravity(Gravity.CENTER_HORIZONTAL);
        LinearLayout.LayoutParams fP = new LinearLayout.LayoutParams(-1, -2);
        fP.topMargin = dp(12);
        root.addView(footer, fP);
    }

    private void loadModel() {
        try {
            OnlineParaformerModelConfig p = new OnlineParaformerModelConfig();
            p.setEncoder("model/encoder.int8.onnx");
            p.setDecoder("model/decoder.int8.onnx");
            OnlineModelConfig m = new OnlineModelConfig();
            m.setParaformer(p);
            m.setTokens("model/tokens.txt");
            m.setNumThreads(2);
            m.setProvider("cpu");
            m.setModelType("paraformer");

            OnlineRecognizerConfig c = new OnlineRecognizerConfig();
            c.setFeatConfig(new FeatureConfig(SAMPLE_RATE, 80, 0.0f));
            c.setModelConfig(m);
            // Commit after a short pause, but show partial results continually.
            c.setEndpointConfig(new EndpointConfig(
                    new EndpointRule(false, 1.6f, 0f),
                    new EndpointRule(true, 0.8f, 0f),
                    new EndpointRule(false, 0f, 18f)));
            c.setEnableEndpoint(true);

            recognizer = new OnlineRecognizer(getAssets(), c);
            ui.post(() -> setStatus("Live model loaded • Initializing accuracy and punctuation…"));
            accuracyEngine = new AccuracyEngine(this);
            modelReady = true;
            // CI-only native inference check. The installed phone app never runs this
            // unless explicitly launched with the ciSmokeTest intent extra.
            if (getIntent().getBooleanExtra("ciSmokeTest", false)) {
                new Thread(this::runOfflineSelfTest, "offline-selftest").start();
            }

            ui.post(() -> {
                setStatus("Offline model ready");
                metricView.setText("Two-pass accuracy correction • Automatic ， and 。");
                recordButton.setEnabled(true);
            });
        } catch (Throwable e) {
            ui.post(() -> {
                setStatus("Could not load model");
                metricView.setText(e.getClass().getSimpleName() + ": " + e.getMessage());
                recordButton.setEnabled(false);
            });
        }
    }


    private void runOfflineSelfTest() {
        OnlineStream stream = null;
        try {
            stream = recognizer.createStream("");
            float[] silentBlock = new float[1600]; // 100 ms at 16 kHz
            int decoded = 0;
            for (int i = 0; i < 60; i++) {
                stream.acceptWaveform(silentBlock, SAMPLE_RATE);
                while (recognizer.isReady(stream)) {
                    recognizer.decode(stream);
                    decoded++;
                }
            }
            stream.inputFinished();
            while (recognizer.isReady(stream)) {
                recognizer.decode(stream);
                decoded++;
            }
            recognizer.getResult(stream);
            // Exercise real SenseVoice inference and neural punctuation in CI.
            String checked = accuracyEngine.recognize(new float[32000]);
            String sentences = accuracyEngine.restorePunctuation("今天我去学校明天我回家");
            if (!sentences.contains("。")) throw new IllegalStateException("Punctuation produced no full stop: " + sentences);
            Log.i(LOG_TAG, "Second-pass and punctuation self-test OK; sample=" + sentences + ", speech=" + checked);
            if (decoded < 1) throw new IllegalStateException("Native recognizer decoded no audio frames");
            Log.i(LOG_TAG, "ASR silent self-test passed, decoded=" + decoded);
            ui.post(() -> setStatus("Offline model ready · Self-test passed"));
        } catch (Throwable err) {
            Log.e(LOG_TAG, "ASR self-test failed", err);
            ui.post(() -> setStatus("ASR self-test failed: " + err.getMessage()));
        } finally {
            if (stream != null) try { stream.release(); } catch (Throwable ignored) {}
        }
    }

    private void startRecording() {
        if (!modelReady || busy) return;
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[] {Manifest.permission.RECORD_AUDIO}, AUDIO_PERMISSION);
            return;
        }
        int min = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT);
        if (min < 0) { setStatus("16 kHz microphone not supported"); return; }
        // Enough buffering to absorb occasional slow CPU inference on A16 5G.
        int capacity = Math.max(min * 2, SAMPLE_RATE * 2 * 3);
        try {
            microphone = new AudioRecord(MediaRecorder.AudioSource.MIC,
                    SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT, capacity);
            if (microphone.getState() != AudioRecord.STATE_INITIALIZED) {
                microphone.release();
                setStatus("Microphone initialization failed");
                Log.e(LOG_TAG, "AudioRecord not initialized");
                return;
            }
            microphone.startRecording();
            Log.i(LOG_TAG, "AudioRecord state after start: " + microphone.getRecordingState());
            recording = true;
            busy = true;
            recordButton.setText("Stop and finish");
            setStatus("● Listening live");
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            final AudioRecord current = microphone;
            new Thread(() -> decodeLoop(current), "live-recognizer").start();
        } catch (Throwable e) {
            setStatus("Microphone error: " + e.getMessage());
            Log.e(LOG_TAG, "Microphone startup exception", e);
            if (microphone != null) { try { microphone.release(); } catch (Throwable ignored) {} }
            microphone = null;
            recording = false;
            busy = false;
        }
    }

    private void stopRecording() {
        recording = false;
        recordButton.setEnabled(false);
        setStatus("Finishing last words…");
        // The AudioRecord will be closed by the worker after the active read.
    }

    private void decodeLoop(AudioRecord audio) {
        OnlineStream stream = null;
        FloatCollector captured = new FloatCollector();
        try {
            stream = recognizer.createStream("");
            short[] pcm16 = new short[1600]; // 100 ms chunks
            while (recording) {
                int size = audio.read(pcm16, 0, pcm16.length);
                if (size == AudioRecord.ERROR_DEAD_OBJECT) throw new IllegalStateException("Microphone disconnected");
                if (size <= 0) continue;
                float[] pcm = new float[size];
                for (int i = 0; i < size; i++) pcm[i] = pcm16[i] / 32768.0f;
                captured.add(pcm);
                long begin = SystemClock.elapsedRealtime();
                stream.acceptWaveform(pcm, SAMPLE_RATE);
                while (recognizer.isReady(stream)) recognizer.decode(stream);
                boolean endpoint = recognizer.isEndpoint(stream);
                String current = recognizer.getResult(stream).getText().trim();
                if (endpoint) {
                    // Lookahead flush for Paraformer, only at short pauses.
                    stream.acceptWaveform(new float[SAMPLE_RATE / 2], SAMPLE_RATE);
                    while (recognizer.isReady(stream)) recognizer.decode(stream);
                    current = recognizer.getResult(stream).getText().trim();
                    queueCorrection(current, captured.drain());
                    recognizer.reset(stream);
                } else {
                    synchronized (transcriptLock) { partial = current; }
                }
                long elapsed = SystemClock.elapsedRealtime() - begin;
                long now = SystemClock.elapsedRealtime();
                if (now - lastUiMs >= 120 || endpoint) {
                    lastUiMs = now;
                    final long decodeMs = elapsed;
                    ui.post(() -> {
                        drawTranscript();
                        metricView.setText("Model processing: " + decodeMs + " ms / 100 ms audio chunk (not end-to-end delay)");
                    });
                }
            }
            stream.inputFinished();
            while (recognizer.isReady(stream)) recognizer.decode(stream);
            String tail = recognizer.getResult(stream).getText().trim();
            queueCorrection(tail, captured.drain());
        } catch (Throwable e) {
            Log.e(LOG_TAG, "Recognition decoding failed", e);
            ui.post(() -> { setStatus("Recognition error: " + e.getMessage()); toast("Transcription stopped"); });
        } finally {
            try { audio.stop(); } catch (Throwable ignored) {}
            try { audio.release(); } catch (Throwable ignored) {}
            if (stream != null) try { stream.release(); } catch (Throwable ignored) {}
            recording = false;
            busy = false;
            ui.post(() -> {
                getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
                recordButton.setText("Start listening");
                recordButton.setEnabled(modelReady);
                setStatus("Ready • Transcript retained on screen");
                drawTranscript();
            });
        }
    }

    private static final class TranscriptSegment {
        String text;
        TranscriptSegment(String text) { this.text = text; }
    }

    private static final class FloatCollector {
        private float[] data = new float[SAMPLE_RATE * 2];
        private int length = 0;
        void add(float[] samples) {
            if (length + samples.length > SAMPLE_RATE * 20) {
                // Bound memory even if an endpoint is missed.
                length = 0;
            }
            while (length + samples.length > data.length)
                data = Arrays.copyOf(data, Math.min(data.length * 2, SAMPLE_RATE * 21));
            System.arraycopy(samples, 0, data, length, samples.length);
            length += samples.length;
        }
        float[] drain() {
            float[] result = Arrays.copyOf(data, length);
            length = 0;
            return result;
        }
    }

    private void queueCorrection(String firstPass, float[] speech) {
        final TranscriptSegment slot;
        final int generation;
        synchronized (transcriptLock) {
            String draft = AccuracyEngine.ensureSentenceEnd(firstPass);
            if (draft.isEmpty()) {
                partial = "";
                return;
            }
            slot = new TranscriptSegment(draft);
            committedSegments.add(slot);
            partial = "";
            generation = transcriptGeneration;
        }
        ui.post(this::drawTranscript);
        if (speech == null || speech.length < 3200) return;
        correctionExecutor.execute(() -> {
            try {
                // Offline SenseVoice sees the whole short utterance and can repair
                // recognition errors made by the low-latency streaming model.
                String secondPass = accuracyEngine.recognize(speech);
                if (!secondPass.isEmpty()) {
                    synchronized (transcriptLock) {
                        if (generation != transcriptGeneration || !committedSegments.contains(slot)) return;
                        slot.text = secondPass;
                    }
                    ui.post(this::drawTranscript);
                }
            } catch (Throwable err) {
                Log.e(LOG_TAG, "Second-pass correction failed; retaining streaming text", err);
            }
        });
    }

    private String transcript() {
        synchronized (transcriptLock) {
            StringBuilder text = new StringBuilder();
            for (TranscriptSegment segment : committedSegments) {
                if (segment.text.isEmpty()) continue;
                if (text.length() > 0) text.append("\n");
                text.append(segment.text);
            }
            if (!partial.isEmpty()) {
                if (text.length() > 0) text.append("\n");
                text.append(partial);
            }
            return text.toString();
        }
    }

    private void drawTranscript() {
        String txt = transcript();
        transcriptView.setText(txt.isEmpty() ? "Listening for speech…" : txt);
        transcriptScroller.post(() -> transcriptScroller.fullScroll(View.FOCUS_DOWN));
    }

    private void setStatus(String s) { stateView.setText(s); }
    private void toast(String s) { Toast.makeText(this, s, Toast.LENGTH_SHORT).show(); }

    @Override public void onRequestPermissionsResult(int request, String[] perms, int[] grants) {
        super.onRequestPermissionsResult(request, perms, grants);
        if (request == AUDIO_PERMISSION) {
            if (grants.length > 0 && grants[0] == PackageManager.PERMISSION_GRANTED) startRecording();
            else toast("Microphone permission is required");
        }
    }

    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request != SAVE_DOCUMENT || result != RESULT_OK || data == null) return;
        try (OutputStream output = getContentResolver().openOutputStream(data.getData())) {
            if (output == null) throw new IllegalStateException("Could not open selected file");
            output.write(transcript().getBytes(StandardCharsets.UTF_8));
            toast("Saved");
        } catch (Exception e) {
            toast("Save failed: " + e.getMessage());
        }
    }

    @Override protected void onDestroy() {
        recording = false;
        super.onDestroy();
        // The recognition worker owns releasing the microphone and stream.
        // The process owns the native model until the activity is destroyed.
    }
}
