package com.example.mandarincallcaptions

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.core.app.NotificationCompat
import java.io.FileOutputStream
import java.util.concurrent.atomic.AtomicBoolean

class InternalAudioCaptionService : Service(), RecognitionListener {

    private var projection: MediaProjection? = null
    private var audioRecord: AudioRecord? = null
    private var recognizer: SpeechRecognizer? = null
    private var pipeRead: ParcelFileDescriptor? = null
    private var pipeWrite: ParcelFileDescriptor? = null
    private var captureThread: Thread? = null
    private val capturing = AtomicBoolean(false)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopCapture()
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_START -> {
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                    sendStatus("手机音频字幕需要 Android 13 或更高版本")
                    stopSelf()
                    return START_NOT_STICKY
                }

                val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0)
                val resultData = if (Build.VERSION.SDK_INT >= 33) {
                    intent.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(EXTRA_RESULT_DATA)
                }

                if (resultCode == 0 || resultData == null) {
                    sendStatus("未获得手机音频捕获权限")
                    stopSelf()
                    return START_NOT_STICKY
                }

                startAsForeground()
                startCapture(resultCode, resultData)
            }
        }
        return START_NOT_STICKY
    }

    private fun startAsForeground() {
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("Mandarin captions")
            .setContentText("正在为手机播放的音频生成中文字幕")
            .setOngoing(true)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun startCapture(resultCode: Int, resultData: Intent) {
        stopCapture(sendStopped = false)

        val manager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        projection = manager.getMediaProjection(resultCode, resultData)?.also { mp ->
            mp.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() {
                    stopCapture()
                    stopSelf()
                }
            }, null)
        }

        val mediaProjection = projection ?: run {
            sendStatus("无法启动手机音频捕获")
            stopSelf()
            return
        }

        val config = AudioPlaybackCaptureConfiguration.Builder(mediaProjection)
            .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
            .addMatchingUsage(AudioAttributes.USAGE_GAME)
            .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
            .build()

        val audioFormat = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setSampleRate(SAMPLE_RATE)
            .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
            .build()

        val minBuffer = AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        ).coerceAtLeast(SAMPLE_RATE)

        audioRecord = AudioRecord.Builder()
            .setAudioFormat(audioFormat)
            .setBufferSizeInBytes(minBuffer * 2)
            .setAudioPlaybackCaptureConfig(config)
            .build()

        val pipe = ParcelFileDescriptor.createPipe()
        pipeRead = pipe[0]
        pipeWrite = pipe[1]

        if (!SpeechRecognizer.isOnDeviceRecognitionAvailable(this)) {
            sendStatus("此设备不支持离线中文语音识别")
            stopCapture()
            return
        }

        recognizer = SpeechRecognizer.createOnDeviceSpeechRecognizer(this).also {
            it.setRecognitionListener(this)
        }

        capturing.set(true)
        audioRecord?.startRecording()
        captureThread = Thread {
            val buffer = ByteArray(minBuffer)
            try {
                FileOutputStream(pipeWrite!!.fileDescriptor).use { output ->
                    while (capturing.get()) {
                        val count = audioRecord?.read(buffer, 0, buffer.size) ?: -1
                        if (count > 0) {
                            output.write(buffer, 0, count)
                            output.flush()
                        } else if (count < 0) {
                            break
                        }
                    }
                }
            } catch (_: Exception) {
                // Stopping capture closes the pipe and naturally ends this loop.
            }
        }.apply {
            name = "PlaybackCaptionAudioPipe"
            start()
        }

        val recognitionIntent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "zh-CN")
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, "zh-CN")
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE, pipeRead)
            putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_CHANNEL_COUNT, 1)
            putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
            putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_SAMPLING_RATE, SAMPLE_RATE)
            putExtra(RecognizerIntent.EXTRA_SEGMENTED_SESSION, RecognizerIntent.EXTRA_AUDIO_SOURCE)
        }

        sendBroadcast(Intent(ACTION_INTERNAL_AUDIO_STATE).setPackage(packageName).putExtra(EXTRA_ACTIVE, true))
        sendStatus("正在听手机音频…")
        recognizer?.startListening(recognitionIntent)
    }

    private fun stopCapture(sendStopped: Boolean = true) {
        capturing.set(false)
        runCatching { audioRecord?.stop() }
        audioRecord?.release()
        audioRecord = null
        captureThread?.interrupt()
        captureThread = null

        runCatching { recognizer?.cancel() }
        recognizer?.destroy()
        recognizer = null

        runCatching { pipeWrite?.close() }
        runCatching { pipeRead?.close() }
        pipeWrite = null
        pipeRead = null

        runCatching { projection?.stop() }
        projection = null

        if (sendStopped) {
            sendBroadcast(Intent(ACTION_INTERNAL_AUDIO_STATE).setPackage(packageName).putExtra(EXTRA_ACTIVE, false))
        }
    }

    override fun onDestroy() {
        stopCapture()
        super.onDestroy()
    }

    override fun onReadyForSpeech(params: Bundle?) = Unit
    override fun onBeginningOfSpeech() = Unit
    override fun onRmsChanged(rmsdB: Float) = Unit
    override fun onBufferReceived(buffer: ByteArray?) = Unit
    override fun onEndOfSpeech() = Unit

    override fun onError(error: Int) {
        sendStatus("手机音频识别暂停，请重新开始")
    }

    override fun onResults(results: Bundle?) {
        emitResult(results, final = true)
    }

    override fun onPartialResults(partialResults: Bundle?) {
        emitResult(partialResults, final = false)
    }

    override fun onSegmentResults(segmentResults: Bundle) {
        emitResult(segmentResults, final = true)
    }

    override fun onEndOfSegmentedSession() {
        sendStatus("手机音频字幕已结束")
    }

    override fun onEvent(eventType: Int, params: Bundle?) = Unit

    private fun emitResult(bundle: Bundle?, final: Boolean) {
        val text = bundle
            ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            ?.firstOrNull()
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: return

        sendBroadcast(
            Intent(ACTION_INTERNAL_CAPTION)
                .setPackage(packageName)
                .putExtra(EXTRA_TEXT, text)
                .putExtra(EXTRA_FINAL, final)
        )
    }

    private fun sendStatus(text: String) {
        sendBroadcast(
            Intent(ACTION_INTERNAL_STATUS)
                .setPackage(packageName)
                .putExtra(EXTRA_TEXT, text)
        )
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    "Internal audio captions",
                    NotificationManager.IMPORTANCE_LOW
                )
            )
        }
    }

    companion object {
        const val ACTION_START = "com.example.mandarincallcaptions.START_INTERNAL_AUDIO"
        const val ACTION_STOP = "com.example.mandarincallcaptions.STOP_INTERNAL_AUDIO"
        const val ACTION_INTERNAL_CAPTION = "com.example.mandarincallcaptions.INTERNAL_CAPTION"
        const val ACTION_INTERNAL_STATUS = "com.example.mandarincallcaptions.INTERNAL_STATUS"
        const val ACTION_INTERNAL_AUDIO_STATE = "com.example.mandarincallcaptions.INTERNAL_STATE"
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_RESULT_DATA = "result_data"
        const val EXTRA_TEXT = "text"
        const val EXTRA_FINAL = "final"
        const val EXTRA_ACTIVE = "active"

        private const val SAMPLE_RATE = 16000
        private const val CHANNEL_ID = "internal_audio_captions"
        private const val NOTIFICATION_ID = 1402
    }
}
