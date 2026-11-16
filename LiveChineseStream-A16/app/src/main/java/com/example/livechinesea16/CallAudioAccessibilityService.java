package com.example.livechinesea16;

import android.accessibilityservice.AccessibilityService;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.os.Build;
import android.util.Log;
import android.view.accessibility.AccessibilityEvent;

/**
 * Deliberately narrow accessibility capability used for hearing access:
 * no window text, screen content, keystrokes, gestures or events are collected.
 * With explicit user opt-in, creates a VOICE_RECOGNITION microphone recorder
 * that Android's concurrent-capture policy may permit during voice/VoIP calls.
 * This only receives acoustic microphone input; it never captures call downlink.
 */
public final class CallAudioAccessibilityService extends AccessibilityService {
    private static final String LOG_TAG = "LiveChineseA16";
    private static volatile CallAudioAccessibilityService connected;

    public static CallAudioAccessibilityService current() { return connected; }
    public static boolean isConnected() { return connected != null; }

    @Override protected void onServiceConnected() {
        super.onServiceConnected();
        connected = this;
        Log.i(LOG_TAG, "CallMode: AccessibilityService connected (no screen content requested)");
    }

    public AudioRecord openCallMicrophone(int sampleRate, int bufferBytes) {
        if (connected != this) throw new IllegalStateException("Accessibility service inactive");
        AudioFormat format = new AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(sampleRate)
                .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                .build();
        AudioRecord.Builder builder = new AudioRecord.Builder()
                .setAudioSource(MediaRecorder.AudioSource.VOICE_RECOGNITION)
                .setAudioFormat(format)
                .setBufferSizeInBytes(bufferBytes);
        if (Build.VERSION.SDK_INT >= 30) {
            builder.setPrivacySensitive(false);
        }
        return builder.build();
    }

    @Override public void onAccessibilityEvent(AccessibilityEvent event) {
        // Intentionally unused. No window content / UI inspection.
    }
    @Override public void onInterrupt() { }
    @Override public boolean onUnbind(android.content.Intent intent) {
        if (connected == this) connected = null;
        Log.i(LOG_TAG, "CallMode: AccessibilityService disconnected");
        return super.onUnbind(intent);
    }
    @Override public void onDestroy() {
        if (connected == this) connected = null;
        super.onDestroy();
    }
}
