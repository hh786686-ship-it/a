package com.example.mandarincallcaptions

import android.Manifest
import android.accessibilityservice.AccessibilityService
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.PixelFormat
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Handler
import android.os.Looper
import android.util.DisplayMetrics
import android.view.Gravity
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.widget.TextView
import androidx.core.content.ContextCompat
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.roundToInt

class CaptionAccessibilityService : AccessibilityService() {
    private var overlay: TextView? = null
    private var overlayParams: WindowManager.LayoutParams? = null
    private val handler = Handler(Looper.getMainLooper())
    private val committedCaptions = ArrayDeque<String>()
    private var engine: SenseVoiceEngine? = null
    private var audioRecord: AudioRecord? = null
    private var micThread: Thread? = null
    private val micRunning = AtomicBoolean(false)
    private var internalAudioActive = false

    private val prefs by lazy { getSharedPreferences(MainActivity.PREFS_NAME, MODE_PRIVATE) }

    private val internalAudioReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                InternalAudioCaptionService.ACTION_INTERNAL_AUDIO_STATE -> {
                    internalAudioActive = intent.getBooleanExtra(InternalAudioCaptionService.EXTRA_ACTIVE, false)
                    if (internalAudioActive) {
                        stopMic()
                        showMessage("正在听手机音频…")
                    } else {
                        startMic()
                    }
                }
                InternalAudioCaptionService.ACTION_INTERNAL_CAPTION -> {
                    intent.getStringExtra(InternalAudioCaptionService.EXTRA_TEXT)?.trim()?.takeIf { it.isNotBlank() }?.let {
                        commitCaption(it)
                    }
                }
                InternalAudioCaptionService.ACTION_INTERNAL_STATUS -> {
                    intent.getStringExtra(InternalAudioCaptionService.EXTRA_TEXT)?.takeIf { it.isNotBlank() }?.let(::showMessage)
                }
            }
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        showOverlay()
        val filter = IntentFilter().apply {
            addAction(InternalAudioCaptionService.ACTION_INTERNAL_AUDIO_STATE)
            addAction(InternalAudioCaptionService.ACTION_INTERNAL_CAPTION)
            addAction(InternalAudioCaptionService.ACTION_INTERNAL_STATUS)
        }
        if (android.os.Build.VERSION.SDK_INT >= 33) registerReceiver(internalAudioReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        else { @Suppress("DEPRECATION") registerReceiver(internalAudioReceiver, filter) }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            showMessage("请允许麦克风权限")
            return
        }
        showMessage("正在加载离线中文模型…")
        Thread {
            try {
                engine = SenseVoiceEngine(this)
                handler.post { startMic() }
            } catch (e: Throwable) {
                showMessage("模型加载失败：${e.javaClass.simpleName}")
            }
        }.start()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit
    override fun onInterrupt() = stopMic()

    private fun startMic() {
        if (micRunning.get() || internalAudioActive || engine == null) return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) return

        val min = AudioRecord.getMinBufferSize(SenseVoiceEngine.SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        audioRecord = try {
            AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, SenseVoiceEngine.SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, min.coerceAtLeast(4096) * 2)
        } catch (_: Throwable) { null }
        if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
            showMessage("无法打开麦克风")
            return
        }
        micRunning.set(true)
        audioRecord?.startRecording()
        showMessage("正在听…")
        micThread = Thread { captureLoop(audioRecord!!, micRunning) }.apply { name = "SenseVoiceMic"; start() }
    }

    private fun captureLoop(record: AudioRecord, running: AtomicBoolean) {
        val chunkSamples = SenseVoiceEngine.SAMPLE_RATE * 3
        val shorts = ShortArray(2048)
        val acc = ArrayList<Float>(chunkSamples)
        while (running.get()) {
            val n = record.read(shorts, 0, shorts.size)
            if (n <= 0) continue
            for (i in 0 until n) acc.add(shorts[i] / 32768f)
            if (acc.size >= chunkSamples) {
                val samples = FloatArray(chunkSamples) { acc[it] }
                acc.subList(0, chunkSamples).clear()
                try {
                    val text = engine?.transcribe(samples).orEmpty()
                    if (text.isNotBlank()) commitCaption(text)
                } catch (e: Throwable) {
                    showMessage("识别错误：${e.javaClass.simpleName}")
                }
            }
        }
    }

    private fun stopMic() {
        micRunning.set(false)
        runCatching { audioRecord?.stop() }
        audioRecord?.release(); audioRecord = null
        micThread?.interrupt(); micThread = null
    }

    private fun commitCaption(text: String) {
        handler.post {
            committedCaptions.addLast(text)
            trimHistory()
            renderCaptions()
        }
    }

    override fun onDestroy() {
        stopMic()
        runCatching { unregisterReceiver(internalAudioReceiver) }
        engine?.release(); engine = null
        removeOverlay()
        super.onDestroy()
    }

    private fun maxLines() = prefs.getInt(MainActivity.KEY_MAX_LINES, 4).coerceIn(1, 6)
    private fun trimHistory() { while (committedCaptions.size > maxLines()) committedCaptions.removeFirst() }

    private fun showOverlay() {
        if (overlay != null) return
        val wm = getSystemService(WINDOW_SERVICE) as WindowManager
        val view = TextView(this).apply {
            text = "字幕准备中…"; setTextColor(0xFFFFFFFF.toInt()); setBackgroundColor(0xE6111111.toInt())
            setPadding(dp(18), dp(14), dp(18), dp(14)); gravity = Gravity.START or Gravity.BOTTOM
        }
        val params = WindowManager.LayoutParams(WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT).apply { gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL; y = dp(80) }
        overlay = view; overlayParams = params; applyOverlaySettings(); wm.addView(view, params)
    }

    private fun applyOverlaySettings() {
        val view = overlay ?: return; val params = overlayParams ?: return
        val wm = getSystemService(WINDOW_SERVICE) as WindowManager
        val width = prefs.getInt(MainActivity.KEY_WIDTH_PERCENT, 90).coerceIn(50,100)
        val height = prefs.getInt(MainActivity.KEY_HEIGHT_PERCENT,25).coerceIn(10,70)
        val font = prefs.getInt(MainActivity.KEY_FONT_SP,24).coerceIn(18,38)
        val metrics = DisplayMetrics(); @Suppress("DEPRECATION") wm.defaultDisplay.getMetrics(metrics)
        params.width=(metrics.widthPixels*(width/100f)).roundToInt(); params.height=(metrics.heightPixels*(height/100f)).roundToInt()
        view.textSize=font.toFloat(); view.maxLines=maxLines(); trimHistory()
        if (view.isAttachedToWindow) runCatching { wm.updateViewLayout(view, params) }
    }

    private fun renderCaptions() { handler.post { applyOverlaySettings(); overlay?.text = committedCaptions.takeLast(maxLines()).joinToString("\n").ifBlank { "正在听…" } } }
    private fun showMessage(text:String) { handler.post { applyOverlaySettings(); overlay?.text=text } }
    private fun removeOverlay() { overlay?.let { v -> runCatching { (getSystemService(WINDOW_SERVICE) as WindowManager).removeView(v) } }; overlay=null }
    private fun dp(v:Int)=(v*resources.displayMetrics.density).roundToInt()
}
