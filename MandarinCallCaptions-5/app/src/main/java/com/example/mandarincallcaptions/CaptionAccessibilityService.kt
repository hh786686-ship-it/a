package com.example.mandarincallcaptions

import android.Manifest
import android.accessibilityservice.AccessibilityService
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.PixelFormat
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.DisplayMetrics
import android.view.Gravity
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.widget.TextView
import androidx.core.content.ContextCompat
import kotlin.math.roundToInt

class CaptionAccessibilityService : AccessibilityService(), RecognitionListener {

    private var recognizer: SpeechRecognizer? = null
    private var overlay: TextView? = null
    private var overlayParams: WindowManager.LayoutParams? = null
    private var listening = false
    private var internalAudioActive = false
    private val handler = Handler(Looper.getMainLooper())
    private val committedCaptions = ArrayDeque<String>()
    private var partialCaption: String? = null


    private val internalAudioReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                InternalAudioCaptionService.ACTION_INTERNAL_AUDIO_STATE -> {
                    internalAudioActive = intent.getBooleanExtra(InternalAudioCaptionService.EXTRA_ACTIVE, false)
                    if (internalAudioActive) {
                        stopListening()
                        partialCaption = null
                        showMessage("正在听手机音频…")
                    } else {
                        if (recognizer != null) startListening()
                    }
                }
                InternalAudioCaptionService.ACTION_INTERNAL_CAPTION -> {
                    val text = intent.getStringExtra(InternalAudioCaptionService.EXTRA_TEXT)?.trim().orEmpty()
                    if (text.isBlank()) return
                    val isFinal = intent.getBooleanExtra(InternalAudioCaptionService.EXTRA_FINAL, false)
                    if (isFinal) {
                        partialCaption = null
                        committedCaptions.addLast(text)
                        trimHistory()
                    } else {
                        partialCaption = text
                    }
                    renderCaptions()
                }
                InternalAudioCaptionService.ACTION_INTERNAL_STATUS -> {
                    val text = intent.getStringExtra(InternalAudioCaptionService.EXTRA_TEXT)
                    if (!text.isNullOrBlank()) showMessage(text)
                }
            }
        }
    }

    private val prefs by lazy {
        getSharedPreferences(MainActivity.PREFS_NAME, MODE_PRIVATE)
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        showOverlay()

        val filter = IntentFilter().apply {
            addAction(InternalAudioCaptionService.ACTION_INTERNAL_AUDIO_STATE)
            addAction(InternalAudioCaptionService.ACTION_INTERNAL_CAPTION)
            addAction(InternalAudioCaptionService.ACTION_INTERNAL_STATUS)
        }
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            registerReceiver(internalAudioReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            registerReceiver(internalAudioReceiver, filter)
        }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            showMessage("请允许麦克风权限")
            return
        }

        if (!SpeechRecognizer.isOnDeviceRecognitionAvailable(this)) {
            showMessage("此设备不支持离线语音识别")
            return
        }

        recognizer = SpeechRecognizer.createOnDeviceSpeechRecognizer(this).also {
            it.setRecognitionListener(this)
        }
        startListening()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

    override fun onInterrupt() {
        stopListening()
    }

    override fun onDestroy() {
        stopListening()
        runCatching { unregisterReceiver(internalAudioReceiver) }
        recognizer?.destroy()
        recognizer = null
        removeOverlay()
        super.onDestroy()
    }

    private fun startListening() {
        if (listening || recognizer == null || internalAudioActive) return

        applyOverlaySettings()

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "zh-CN")
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, "zh-CN")
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        }

        listening = true
        if (committedCaptions.isEmpty()) showMessage("正在听…") else renderCaptions()
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
        if (committedCaptions.isEmpty()) showMessage("正在听…")
    }

    override fun onBeginningOfSpeech() = Unit
    override fun onRmsChanged(rmsdB: Float) = Unit
    override fun onBufferReceived(buffer: ByteArray?) = Unit

    override fun onEndOfSpeech() {
        listening = false
    }

    override fun onError(error: Int) {
        partialCaption = null
        renderCaptions()
        restartSoon(if (error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY) 900 else 350)
    }

    override fun onResults(results: Bundle?) {
        val text = results
            ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            ?.firstOrNull()
            ?.trim()

        partialCaption = null
        if (!text.isNullOrBlank()) {
            committedCaptions.addLast(text)
            trimHistory()
            renderCaptions()
        }
        restartSoon()
    }

    override fun onPartialResults(partialResults: Bundle?) {
        partialCaption = partialResults
            ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            ?.firstOrNull()
            ?.trim()
            ?.takeIf { it.isNotBlank() }
        renderCaptions()
    }

    override fun onEvent(eventType: Int, params: Bundle?) = Unit

    private fun maxLines(): Int = prefs.getInt(MainActivity.KEY_MAX_LINES, 4).coerceIn(1, 6)

    private fun trimHistory() {
        val max = maxLines()
        while (committedCaptions.size > max) {
            committedCaptions.removeFirst()
        }
    }

    private fun showOverlay() {
        if (overlay != null) return

        val wm = getSystemService(WINDOW_SERVICE) as WindowManager
        val textView = TextView(this).apply {
            text = "字幕准备中…"
            setTextColor(0xFFFFFFFF.toInt())
            setBackgroundColor(0xE6111111.toInt())
            setPadding(dp(18), dp(14), dp(18), dp(14))
            gravity = Gravity.START or Gravity.BOTTOM
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            x = 0
            y = dp(80)
        }

        overlay = textView
        overlayParams = params
        applyOverlaySettings()
        wm.addView(textView, params)
    }

    private fun applyOverlaySettings() {
        val view = overlay ?: return
        val params = overlayParams ?: return
        val wm = getSystemService(WINDOW_SERVICE) as WindowManager

        val widthPercent = prefs.getInt(MainActivity.KEY_WIDTH_PERCENT, 90).coerceIn(50, 100)
        val heightPercent = prefs.getInt(MainActivity.KEY_HEIGHT_PERCENT, 25).coerceIn(10, 70)
        val fontSp = prefs.getInt(MainActivity.KEY_FONT_SP, 24).coerceIn(18, 38)
        val lines = maxLines()

        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        wm.defaultDisplay.getMetrics(metrics)
        params.width = (metrics.widthPixels * (widthPercent / 100f)).roundToInt()
        params.height = (metrics.heightPixels * (heightPercent / 100f)).roundToInt()

        view.textSize = fontSp.toFloat()
        view.maxLines = lines
        trimHistory()

        if (view.isAttachedToWindow) {
            runCatching { wm.updateViewLayout(view, params) }
        }
    }

    private fun renderCaptions() {
        handler.post {
            applyOverlaySettings()
            val max = maxLines()
            val visible = committedCaptions.toMutableList()
            partialCaption?.let { visible.add(it) }
            val finalVisible = visible.takeLast(max)
            overlay?.text = if (finalVisible.isEmpty()) "正在听…" else finalVisible.joinToString("\n")
        }
    }

    private fun showMessage(text: String) {
        handler.post {
            applyOverlaySettings()
            overlay?.text = text
        }
    }

    private fun removeOverlay() {
        val view = overlay ?: return
        val wm = getSystemService(WINDOW_SERVICE) as WindowManager
        runCatching { wm.removeView(view) }
        overlay = null
        overlayParams = null
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).roundToInt()
}
