package com.example.livechinesea16;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.drawable.GradientDrawable;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.AudioManager;
import android.media.AudioRecordingConfiguration;
import java.util.List;
import android.media.MediaRecorder;
import android.os.Binder;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.text.Layout;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.k2fsa.sherpa.onnx.EndpointConfig;
import com.k2fsa.sherpa.onnx.EndpointRule;
import com.k2fsa.sherpa.onnx.FeatureConfig;
import com.k2fsa.sherpa.onnx.OnlineModelConfig;
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig;
import com.k2fsa.sherpa.onnx.OnlineRecognizer;
import com.k2fsa.sherpa.onnx.OnlineRecognizerConfig;
import com.k2fsa.sherpa.onnx.OnlineStream;

import java.util.ArrayList;

public final class CaptionService extends Service {
    public static final String ACTION_START = "com.example.livechinesea16.START";
    public static final String ACTION_CLOSE = "com.example.livechinesea16.CLOSE";
    public static final String ACTION_STOP_MIC = "com.example.livechinesea16.STOP_MIC";
    public static final String PREFS = "floating_captions";
    public static final String KEY_WIDTH = "max_width_percent";
    public static final String KEY_LINES = "max_visible_lines";
    public static final String KEY_FONT = "font_sp";
    public static final String KEY_CALL_MODE = "call_mode_enabled";
    private static final String LOG_TAG = "LiveChineseA16";
    private static final String CHANNEL = "live_chinese_microphone";
    private static final int NOTIFICATION = 217;
    private static final int SAMPLE_RATE = 16000;
    // A short-lived, process-local warm recognizer survives closing the overlay.
    // There is NO microphone recording, decode loop, or foreground service while idle.
    // Android can still reclaim this process at any time.
    private static final Object WARM_LOCK = new Object();
    private static final long WARM_IDLE_MS = 90_000L;
    private static final Handler WARM_HANDLER = new Handler(Looper.getMainLooper());
    private static OnlineRecognizer warmRecognizer;
    private static int warmGeneration;
    private static boolean warmCachedIdle;
    private static void activateWarmModel(OnlineRecognizer model) {
        synchronized (WARM_LOCK) {
            warmRecognizer = model;
            warmCachedIdle = false;
            warmGeneration++; // Invalidates pending release callbacks.
        }
    }
    private static OnlineRecognizer getWarmModel() {
        synchronized (WARM_LOCK) {
            if (warmRecognizer == null) return null;
            warmCachedIdle = false;
            warmGeneration++;
            return warmRecognizer;
        }
    }
    private static void idleWarmModel(OnlineRecognizer model) {
        if (model == null) return;
        final int generation;
        synchronized (WARM_LOCK) {
            if (warmRecognizer != model) return;
            warmCachedIdle = true;
            generation = ++warmGeneration;
        }
        WARM_HANDLER.postDelayed(() -> {
            synchronized (WARM_LOCK) {
                if (warmGeneration == generation && warmCachedIdle) {
                    // Do not hold a 200 MB+ model indefinitely after the user exits.
                    warmRecognizer = null;
                    warmCachedIdle = false;
                    Log.i(LOG_TAG, "Warm model idle cache expired");
                }
            }
        }, WARM_IDLE_MS);
    }
    private final Handler main = new Handler(Looper.getMainLooper());
    private final IBinder binder = new LocalBinder();
    private final Object lock = new Object();
    private final ArrayList<String> completed = new ArrayList<>();
    private volatile boolean ready, listening, busy, foreground, pendingStart;
    private volatile String status = "Loading Mandarin streaming Zipformer…";
    private volatile String metric = "Native streaming • offline • Mandarin only";
    private String partial = "";
    private volatile OnlineRecognizer recognizer;
    private volatile AudioRecord microphone;
    private Listener listener;
    private boolean testRequested;
    private long lastUpdate;
    private WindowManager windows;
    private WindowManager.LayoutParams overlayParams;
    private LinearLayout panel;
    private TextView captions;
    private DragDots dots;
    private ScaleGestureDetector scaleDetector;
    private float fontSp;
    private int maxLines, widthPct;
    private boolean closing;
    private int overlayScrollBack = 0; // Number of visual lines behind the newest captions
    private float historyTouchY;
    private boolean historyTouchMoved;
    private long lastCaptionTap;

    public interface Listener { void onChanged(); }
    public final class LocalBinder extends Binder {
        public CaptionService service() { return CaptionService.this; }
    }

    @Override public void onCreate() {
        super.onCreate();
        SharedPreferences p = getSharedPreferences(PREFS, MODE_PRIVATE);
        widthPct = clamp(p.getInt(KEY_WIDTH, 78), 45, 96);
        maxLines = clamp(p.getInt(KEY_LINES, 5), 1, 5);
        fontSp = clampFloat(p.getFloat(KEY_FONT, 24f), 12f, 40f);
        String saved = p.getString("last_transcript", "");
        if (!saved.isEmpty()) {
            for (String line : saved.split("\n", -1)) {
                if (!line.isEmpty()) completed.add(line);
            }
        }
        makeNotificationChannel();
        OnlineRecognizer cached = getWarmModel();
        if (cached != null) {
            recognizer = cached;
            ready = true;
            metric = "Mandarin-only Zipformer · cached model reused (no reload)";
            status = "Offline model ready · fast restart";
            Log.i(LOG_TAG, "QA_MODEL_REUSED without loading offline ONNX weights");
        } else {
            new Thread(this::loadModel, "streaming-model-loader").start();
        }
    }
    @Override public IBinder onBind(Intent intent) { return binder; }
    @Override public boolean onUnbind(Intent intent) { listener = null; return super.onUnbind(intent); }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? "" : intent.getAction();
        if (ACTION_CLOSE.equals(action)) {
            closeOverlayAndStop();
            return START_NOT_STICKY;
        }
        if (ACTION_STOP_MIC.equals(action)) {
            stopListening();
            return START_STICKY;
        }
        if (ACTION_START.equals(action)) {
            closing = false;
            if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                setStatus("Microphone permission is required");
                stopSelf();
                return START_NOT_STICKY;
            }
            // Must enter microphone foreground-service mode while Activity is visible.
            if (!foreground) {
                Notification n = notification();
                if (Build.VERSION.SDK_INT >= 29) {
                    startForeground(NOTIFICATION, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE);
                } else {
                    startForeground(NOTIFICATION, n);
                }
                foreground = true;
            }
            if (Settings.canDrawOverlays(this)) showOverlay();
            else setStatus("Allow display over other apps in Settings");
            pendingStart = true;
            if (ready) startListening();
        }
        return START_STICKY;
    }

    public void setListener(Listener next) {
        listener = next;
        fireChanged();
    }
    public boolean isReady() { return ready; }
    public boolean isListening() { return listening || pendingStart; }
    public String getStatus() { return status; }
    public String getMetric() { return metric; }
    public String getTranscript() {
        synchronized (lock) {
            StringBuilder out = new StringBuilder();
            for (String s : completed) {
                if (!s.isEmpty()) {
                    if (out.length() != 0) out.append('\n');
                    out.append(s);
                }
            }
            if (!partial.isEmpty()) {
                if (out.length() != 0) out.append('\n');
                out.append(partial);
            }
            return out.toString();
        }
    }
    public void clear() {
        synchronized (lock) { completed.clear(); partial = ""; }
        persistTranscript();
        fireChanged();
    }
    public void updateSettings() {
        SharedPreferences p = getSharedPreferences(PREFS, MODE_PRIVATE);
        widthPct = clamp(p.getInt(KEY_WIDTH, 78), 45, 96);
        maxLines = clamp(p.getInt(KEY_LINES, 5), 1, 5);
        fontSp = clampFloat(p.getFloat(KEY_FONT, 24f), 12f, 40f);
        if (panel != null) {
            overlayParams.width = overlayWidth();
            windows.updateViewLayout(panel, overlayParams);
            captions.setTextSize(fontSp);
            captions.setMaxLines(maxLines);
            refreshCaptionText();
        }
        fireChanged();
    }

    private void setStatus(String s) {
        status = s;
        fireChanged();
    }
    private void fireChanged() {
        main.post(() -> {
            if (panel != null) refreshCaptionText();
            if (listener != null) listener.onChanged();
        });
    }
    private void loadModel() {
        try {
            OnlineTransducerModelConfig t = new OnlineTransducerModelConfig();
            t.setEncoder("model/encoder.int8.onnx");
            t.setDecoder("model/decoder.onnx");
            t.setJoiner("model/joiner.int8.onnx");
            OnlineModelConfig m = new OnlineModelConfig();
            m.setTransducer(t);
            m.setTokens("model/tokens.txt");
            m.setNumThreads(2);
            m.setProvider("cpu");
            m.setModelType("zipformer2");
            OnlineRecognizerConfig c = new OnlineRecognizerConfig();
            c.setFeatConfig(new FeatureConfig(SAMPLE_RATE, 80, 0.0f));
            c.setModelConfig(m);
            c.setEndpointConfig(new EndpointConfig(
                    new EndpointRule(false, 1.6f, 0f),
                    new EndpointRule(true, 0.8f, 0f),
                    new EndpointRule(false, 0f, 18f)));
            c.setEnableEndpoint(true);
            OnlineRecognizer fresh = new OnlineRecognizer(getAssets(), c);
            recognizer = fresh;
            activateWarmModel(fresh);
            ready = true;
            metric = "Mandarin-only true streaming Zipformer • 100% offline";
            setStatus("Offline model ready");
            if (testRequested) requestSelfTest();
            if (pendingStart) main.post(this::startListening);
        } catch (Throwable e) {
            Log.e(LOG_TAG, "Model load failed", e);
            setStatus("Could not load model: " + e.getMessage());
            pendingStart = false;
        }
    }

    public void requestSelfTest() {
        if (!ready) { testRequested = true; return; }
        testRequested = false;
        new Thread(() -> {
            OnlineStream s = null;
            try {
                s = recognizer.createStream("");
                int decoded = 0;
                for (int i = 0; i < 60; i++) {
                    s.acceptWaveform(new float[1600], SAMPLE_RATE);
                    while (recognizer.isReady(s)) { recognizer.decode(s); decoded++; }
                }
                s.inputFinished();
                while (recognizer.isReady(s)) { recognizer.decode(s); decoded++; }
                recognizer.getResult(s);
                if (decoded == 0) throw new IllegalStateException("No native decoder frames");
                Log.i(LOG_TAG, "ASR silent self-test passed, decoded=" + decoded);
                setStatus("Offline model ready · Self-test passed");
            } catch (Throwable err) {
                Log.e(LOG_TAG, "ASR self-test failed", err);
                setStatus("ASR self-test failed: " + err.getMessage());
            } finally {
                if (s != null) try { s.release(); } catch (Throwable ignored) {}
            }
        }, "asr-ci-self-test").start();
    }

    private void startListening() {
        if (!ready || listening || busy || !foreground) return;
        pendingStart = false;
        final boolean callMode = getSharedPreferences(PREFS, MODE_PRIVATE)
                .getBoolean(KEY_CALL_MODE, false);
        final CallAudioAccessibilityService accessibility = CallAudioAccessibilityService.current();
        if (callMode && accessibility == null) {
            setStatus("Call Mode requires the enabled Live Chinese accessibility service");
            Log.w(LOG_TAG, "CallMode: accessibility service not connected; refusing to silently fall back to MIC");
            return;
        }
        int minimum = AudioRecord.getMinBufferSize(SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
        if (minimum <= 0) { setStatus("16 kHz microphone unavailable"); return; }
        try {
            int bufferSize = Math.max(minimum * 2, SAMPLE_RATE * 6);
            // In Call Mode, AudioRecord MUST be constructed by the active
            // AccessibilityService with VOICE_RECOGNITION source. Ordinary
            // background microphone recording may be silenced during calls.
            // This still only records acoustic microphone audio, NOT the call downlink.
            AudioRecord a = callMode
                    ? accessibility.openCallMicrophone(SAMPLE_RATE, bufferSize)
                    : new AudioRecord(MediaRecorder.AudioSource.MIC, SAMPLE_RATE,
                            AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
                            bufferSize);
            if (a == null) {
                setStatus("Call microphone could not be opened");
                return;
            }
            if (a.getState() != AudioRecord.STATE_INITIALIZED) {
                a.release();
                setStatus("Microphone initialization failed");
                return;
            }
            if (Build.VERSION.SDK_INT >= 29) {
                a.registerAudioRecordingCallback(getMainExecutor(),
                    new AudioManager.AudioRecordingCallback() {
                        @Override public void onRecordingConfigChanged(
                                List<AudioRecordingConfiguration> configurations) {
                            AudioRecordingConfiguration current = a.getActiveRecordingConfiguration();
                            if (current != null && current.isClientSilenced()) {
                                setStatus("Android muted our microphone input during the call");
                                Log.w(LOG_TAG, "CallMode: Android silenced this audio capture");
                            } else if (current != null && listening) {
                                setStatus(callMode ? "● Call Mode mic active — use speakerphone"
                                        : "● Listening live");
                            }
                        }
                    });
            }
            a.startRecording();
            Log.i(LOG_TAG, "AudioRecord state after start: " + a.getRecordingState());
            Log.i(LOG_TAG, callMode
                    ? "CallMode: VOICE_RECOGNITION microphone opened by AccessibilityService"
                    : "NormalMode: MIC microphone opened by CaptionService");
            microphone = a;
            listening = true;
            busy = true;
            setStatus(callMode
                    ? "● Call Mode listening — speakerphone ON; remote voice not guaranteed"
                    : "● Listening live");
            new Thread(() -> decodeLoop(a), "live-recognizer").start();
            refreshNotification();
        } catch (Throwable e) {
            Log.e(LOG_TAG, "Microphone startup exception", e);
            setStatus("Microphone error: " + e.getMessage());
            listening = false;
            busy = false;
        }
    }
    public void stopListening() {
        pendingStart = false;
        listening = false;
        setStatus("Microphone stopped · Overlay pinned (model ready for fast restart)");
        refreshNotification();
    }

    private void decodeLoop(AudioRecord a) {
        OnlineStream stream = null;
        try {
            stream = recognizer.createStream("");
            short[] shorts = new short[1600];
            while (listening) {
                int size = a.read(shorts, 0, shorts.length);
                if (size == AudioRecord.ERROR_DEAD_OBJECT) throw new IllegalStateException("Mic disconnected");
                if (size <= 0) continue;
                float[] pcm = new float[size];
                for (int i = 0; i < size; i++) pcm[i] = shorts[i] / 32768f;
                long begin = SystemClock.elapsedRealtime();
                stream.acceptWaveform(pcm, SAMPLE_RATE);
                while (recognizer.isReady(stream)) recognizer.decode(stream);
                boolean endpoint = recognizer.isEndpoint(stream);
                String candidate = recognizer.getResult(stream).getText().trim();
                if (endpoint) {
                    stream.acceptWaveform(new float[SAMPLE_RATE / 2], SAMPLE_RATE);
                    while (recognizer.isReady(stream)) recognizer.decode(stream);
                    candidate = recognizer.getResult(stream).getText().trim();
                    commit(candidate);
                    recognizer.reset(stream);
                } else {
                    synchronized (lock) { partial = candidate; }
                }
                long now = SystemClock.elapsedRealtime();
                if (now - lastUpdate > 120 || endpoint) {
                    lastUpdate = now;
                    metric = "Model processing: " + (now - begin) +
                            " ms / 100 ms audio chunk (not total delay)";
                    fireChanged();
                }
            }
            stream.inputFinished();
            while (recognizer.isReady(stream)) recognizer.decode(stream);
            commit(recognizer.getResult(stream).getText().trim());
        } catch (Throwable e) {
            Log.e(LOG_TAG, "Recognition decoding failed", e);
            setStatus("Recognition error: " + e.getMessage());
        } finally {
            try { a.stop(); } catch (Throwable ignored) {}
            try { a.release(); } catch (Throwable ignored) {}
            if (stream != null) try { stream.release(); } catch (Throwable ignored) {}
            microphone = null;
            listening = false;
            busy = false;
            persistTranscript();
            if (!closing && foreground) setStatus("Paused · Overlay stays on screen");
            fireChanged();
        }
    }
    private void commit(String text) {
        if (text == null || text.isEmpty()) {
            synchronized (lock) { partial = ""; }
            return;
        }
        synchronized (lock) {
            completed.add(AccuracyEngine.ensureSentenceEnd(text));
            partial = "";
        }
        persistTranscript();
        fireChanged();
    }
    private void persistTranscript() {
        String text = getTranscript();
        // Avoid putting a giant novel into SharedPreferences.
        if (text.length() > 150000) text = text.substring(text.length() - 150000);
        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                .putString("last_transcript", text).apply();
    }

    private void makeNotificationChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel c = new NotificationChannel(CHANNEL, "Live captions microphone",
                    NotificationManager.IMPORTANCE_LOW);
            c.setDescription("Keeps offline live captions active over other apps");
            ((NotificationManager) getSystemService(NOTIFICATION_SERVICE)).createNotificationChannel(c);
        }
    }
    private Notification notification() {
        Intent app = new Intent(this, MainActivity.class);
        PendingIntent open = PendingIntent.getActivity(this, 0, app,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        Intent close = new Intent(this, CaptionService.class).setAction(ACTION_CLOSE);
        PendingIntent quit = PendingIntent.getService(this, 1, close,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        final boolean callMode = getSharedPreferences(PREFS, MODE_PRIVATE)
                .getBoolean(KEY_CALL_MODE, false);
        return new Notification.Builder(this, CHANNEL)
                .setSmallIcon(android.R.drawable.ic_btn_speak_now)
                .setContentTitle("Live Chinese Offline")
                .setContentText(listening
                        ? (callMode ? "Call Mode mic • use speakerphone • captions pinned"
                                : "Listening • floating subtitles pinned")
                        : "Floating subtitles pinned • microphone paused")
                .setContentIntent(open)
                .setOngoing(true)
                .addAction(new Notification.Action.Builder(
                        android.R.drawable.ic_menu_close_clear_cancel, "Close overlay", quit).build())
                .build();
    }
    private void refreshNotification() {
        if (foreground) {
            ((NotificationManager) getSystemService(NOTIFICATION_SERVICE))
                    .notify(NOTIFICATION, notification());
        }
    }

    private int dp(float v) { return Math.round(v * getResources().getDisplayMetrics().density); }
    private static int clamp(int a, int lo, int hi) { return Math.max(lo, Math.min(hi, a)); }
    private static float clampFloat(float a, float lo, float hi) { return Math.max(lo, Math.min(hi, a)); }
    private int screenWidth() {
        DisplayMetrics d = new DisplayMetrics();
        windows.getDefaultDisplay().getMetrics(d);
        return d.widthPixels;
    }
    private int screenHeight() {
        DisplayMetrics d = new DisplayMetrics();
        windows.getDefaultDisplay().getMetrics(d);
        return d.heightPixels;
    }
    private int overlayWidth() {
        return clamp(screenWidth() * widthPct / 100, dp(155), screenWidth() - dp(10));
    }

    private void showOverlay() {
        if (panel != null) return;
        windows = (WindowManager) getSystemService(WINDOW_SERVICE);
        overlayScrollBack = 0;
        panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.HORIZONTAL);
        panel.setGravity(Gravity.CENTER_VERTICAL);
        panel.setMinimumHeight(dp(48));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.argb(210, 51, 54, 61));
        bg.setCornerRadius(dp(13));
        panel.setBackground(bg);
        panel.setElevation(dp(9));

        captions = new TextView(this);
        captions.setTextColor(Color.WHITE);
        captions.setTextSize(fontSp);
        captions.setMaxLines(maxLines);
        captions.setIncludeFontPadding(false);
        captions.setGravity(Gravity.CENTER_VERTICAL);
        captions.setPadding(dp(12), dp(9), dp(4), dp(9));
        captions.setLineSpacing(0, 1.08f);
        panel.addView(captions, new LinearLayout.LayoutParams(0, -2, 1f));

        dots = new DragDots(this);
        panel.addView(dots, new LinearLayout.LayoutParams(dp(42), dp(58)));
        dots.setOnTouchListener(new View.OnTouchListener() {
            float startRawX, startRawY;
            int originX, originY;
            boolean moved;
            @Override public boolean onTouch(View v, MotionEvent e) {
                if (overlayParams == null) return false;
                switch (e.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        startRawX = e.getRawX();
                        startRawY = e.getRawY();
                        originX = overlayParams.x;
                        originY = overlayParams.y;
                        moved = false;
                        return true;
                    case MotionEvent.ACTION_MOVE:
                        int dx = Math.round(e.getRawX() - startRawX);
                        int dy = Math.round(e.getRawY() - startRawY);
                        moved |= Math.abs(dx) + Math.abs(dy) > dp(8);
                        overlayParams.x = clamp(originX + dx, 0, screenWidth() - overlayParams.width);
                        overlayParams.y = clamp(originY + dy, 0,
                                Math.max(0, screenHeight() - dp(20)));
                        try { windows.updateViewLayout(panel, overlayParams); }
                        catch (IllegalArgumentException ignored) {}
                        return true;
                    case MotionEvent.ACTION_UP:
                        // Only dropping on the bottom edge closes captions; normal movement never hides them.
                        if (moved && overlayParams.y + panel.getHeight() >= screenHeight() - dp(24)) {
                            closeOverlayAndStop();
                        }
                        return true;
                    case MotionEvent.ACTION_CANCEL: return true;
                }
                return true;
            }
        });
        scaleDetector = new ScaleGestureDetector(this,
                new ScaleGestureDetector.SimpleOnScaleGestureListener() {
                    @Override public boolean onScale(ScaleGestureDetector d) {
                        float previous = fontSp;
                        fontSp = clampFloat(fontSp * d.getScaleFactor(), 12f, 40f);
                        if (Math.abs(previous - fontSp) >= 0.05f) {
                            captions.setTextSize(fontSp);
                            getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                                    .putFloat(KEY_FONT, fontSp).apply();
                            refreshCaptionText();
                            if (listener != null) listener.onChanged();
                        }
                        return true;
                    }
                });
        captions.setOnTouchListener((v, e) -> {
            scaleDetector.onTouchEvent(e);
            if (scaleDetector.isInProgress() || e.getPointerCount() > 1) return true;
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    historyTouchY = e.getY();
                    historyTouchMoved = false;
                    return true;
                case MotionEvent.ACTION_MOVE:
                    float delta = e.getY() - historyTouchY;
                    if (Math.abs(delta) >= dp(22)) {
                        // Swipe down for older subtitles; swipe up for newer subtitles.
                        overlayScrollBack = Math.max(0, overlayScrollBack +
                                (delta > 0 ? 2 : -2));
                        historyTouchY = e.getY();
                        historyTouchMoved = true;
                        refreshCaptionText();
                    }
                    return true;
                case MotionEvent.ACTION_UP:
                    // Double-tapping the subtitle area resets history to live.
                    long now = SystemClock.uptimeMillis();
                    if (!historyTouchMoved && now - lastCaptionTap < 350) {
                        overlayScrollBack = 0;
                        refreshCaptionText();
                    }
                    lastCaptionTap = now;
                    return true;
                default: return true;
            }
        });
        overlayParams = new WindowManager.LayoutParams(
                overlayWidth(), WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE |
                        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT);
        overlayParams.gravity = Gravity.TOP | Gravity.LEFT;
        overlayParams.x = Math.max(0, (screenWidth() - overlayParams.width) / 2);
        overlayParams.y = dp(110);
        overlayParams.setTitle("LiveChineseCaptionOverlay");
        windows.addView(panel, overlayParams);
        panel.post(this::refreshCaptionText);
    }

    private void refreshCaptionText() {
        if (captions == null) return;
        String full = getTranscript();
        if (full.isEmpty()) {
            captions.setText(listening ? "聆聽中…" : "字幕已固定 · 等待開始");
            return;
        }
        // Retain much more history for scrolling, without layout work on the
        // entire transcript after a long session.
        if (full.length() > 20000) full = full.substring(full.length() - 20000);
        int available = captions.getWidth() - captions.getPaddingLeft()
                - captions.getPaddingRight();
        if (available <= dp(30)) available = overlayWidth() - dp(75);
        TextPaint paint = captions.getPaint();
        StaticLayout layout = StaticLayout.Builder.obtain(full, 0, full.length(),
                paint, Math.max(dp(30), available))
                .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                .setIncludePad(false).build();
        int totalLines = layout.getLineCount();
        int visible = Math.min(maxLines, Math.max(1, totalLines));
        overlayScrollBack = clamp(overlayScrollBack, 0, Math.max(0, totalLines - visible));
        int startLine = Math.max(0, totalLines - visible - overlayScrollBack);
        int endLine = Math.min(totalLines, startLine + visible);
        int startChar = layout.getLineStart(startLine);
        int endChar = layout.getLineEnd(endLine - 1);
        captions.setText(full.substring(startChar, endChar).trim());
    }

    private final class DragDots extends View {
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        DragDots(Context c) { super(c); setContentDescription("Six dots: drag, move down to close"); }
        @Override protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            p.setColor(Color.rgb(234, 235, 239));
            float cx = getWidth() / 2f, cy = getHeight() / 2f;
            for (int row = -1; row <= 1; row++) {
                for (int col = -1; col <= 1; col += 2) {
                    canvas.drawCircle(cx + col * dp(5), cy + row * dp(8), dp(2.3f), p);
                }
            }
        }
    }

    private void closeOverlayAndStop() {
        if (closing) return;
        closing = true;
        pendingStart = false;
        listening = false;
        // Shut down the capture immediately; the decoder thread will release it.
        AudioRecord active = microphone;
        if (active != null) {
            try { active.stop(); } catch (IllegalStateException ignored) {}
        }
        persistTranscript();
        if (panel != null) {
            try { windows.removeViewImmediate(panel); }
            catch (IllegalArgumentException ignored) {}
            panel = null;
            captions = null;
            dots = null;
            overlayParams = null;
        }
        if (foreground) {
            stopForeground(STOP_FOREGROUND_REMOVE);
            foreground = false;
        }
        setStatus("Overlay closed · microphone stopped");
        stopSelf();
    }
    @Override public void onDestroy() {
        listening = false;
        pendingStart = false;
        if (panel != null) {
            try { windows.removeViewImmediate(panel); } catch (Throwable ignored) {}
            panel = null;
        }
        persistTranscript();
        // The recognizer stays cached for 90 seconds for a fast reopening.
        // Cache only while the process remains alive; it is automatically
        // discarded on Android process death.
        // Wait for the decoder thread to finish before offering its recognizer
        // to the next service instance. Otherwise old/new services could decode
        // with the same native state simultaneously.
        final OnlineRecognizer closingRecognizer = recognizer;
        if (busy) {
            main.postDelayed(new Runnable() {
                private int attempts;
                @Override public void run() {
                    if (!busy) {
                        idleWarmModel(closingRecognizer);
                    } else if (++attempts < 100) {
                        main.postDelayed(this, 100);
                    } else {
                        Log.w(LOG_TAG, "Busy decoder prevented warm-model caching");
                    }
                }
            }, 100);
        } else {
            idleWarmModel(closingRecognizer);
        }
        super.onDestroy();
    }
}
