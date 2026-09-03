package com.example.mandarincallcaptions

import android.Manifest
import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.PixelFormat
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.view.Gravity
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.widget.TextView
import androidx.core.content.ContextCompat

class CaptionAccessibilityService : AccessibilityService(), RecognitionListener {

    private var recognizer: SpeechRecognizer? = null
    private var overlay: TextView? = null
    private var listening = false
    private val handler = Handler(Looper.getMainLooper())

    override fun onServiceConnected() {
        super.onServiceConnected()
        showOverlay()

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            setCaption("请在应用中允许麦克风权限。\nPlease allow microphone permission in the app.")
            return
        }

        if (!SpeechRecognizer.isOnDeviceRecognitionAvailable(this)) {
            setCaption("此设备没有可用的离线语音识别。\nNo on-device speech recognizer is available.")
            // We deliberately do not silently switch to a network recognizer
            // because call audio can contain sensitive information.
            return
        }

        recognizer = SpeechRecognizer.createOnDeviceSpeechRecognizer(this).also {
            it.setRecognitionListener(this)
        }
        startListening()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // The service is used primarily to retain permitted microphone capture
        // during calls and to host the accessibility overlay.
    }

    override fun onInterrupt() {
        stopListening()
    }

    override fun onDestroy() {
        stopListening()
        recognizer?.destroy()
        recognizer = null
        removeOverlay()
        super.onDestroy()
    }

    private fun startListening() {
        if (listening || recognizer == null) return

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(
                RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM
            )
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "zh-CN")
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, "zh-CN")
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
        }

        listening = true
        setCaption("正在听…")
        recognizer?.startListening(intent)
    }

    private fun restartSoon(delayMs: Long = 350) {
        listening = false
        handler.removeCallbacksAndMessages(null)
        handler.postDelayed({ startListening() }, delayMs)
    }

    private fun stopListening() {
        handler.removeCallbacksAndMessages(null)
        listening = false
        recognizer?.cancel()
    }

    override fun onReadyForSpeech(params: Bundle?) {
        setCaption("正在听…")
    }

    override fun onBeginningOfSpeech() = Unit
    override fun onRmsChanged(rmsdB: Float) = Unit
    override fun onBufferReceived(buffer: ByteArray?) = Unit

    override fun onEndOfSpeech() {
        listening = false
    }

    override fun onError(error: Int) {
        // SpeechRecognizer sessions are finite; restart for continuous captions.
        restartSoon(if (error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY) 900 else 350)
    }

    override fun onResults(results: Bundle?) {
        val text = results
            ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            ?.firstOrNull()

        if (!text.isNullOrBlank()) setCaption(text)
        restartSoon()
    }

    override fun onPartialResults(partialResults: Bundle?) {
        val text = partialResults
            ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            ?.firstOrNull()

        if (!text.isNullOrBlank()) setCaption(text)
    }

    override fun onEvent(eventType: Int, params: Bundle?) = Unit

    private fun showOverlay() {
        if (overlay != null) return

        val wm = getSystemService(WINDOW_SERVICE) as WindowManager
        val textView = TextView(this).apply {
            text = "字幕准备中…"
            textSize = 22f
            setTextColor(0xFFFFFFFF.toInt())
            setBackgroundColor(0xDD111111.toInt())
            setPadding(28, 20, 28, 20)
            gravity = Gravity.CENTER
            maxLines = 4
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.BOTTOM
            x = 0
            y = 80
        }

        wm.addView(textView, params)
        overlay = textView
    }

    private fun removeOverlay() {
        val view = overlay ?: return
        val wm = getSystemService(WINDOW_SERVICE) as WindowManager
        runCatching { wm.removeView(view) }
        overlay = null
    }

    private fun setCaption(text: String) {
        handler.post { overlay?.text = text }
    }
}
