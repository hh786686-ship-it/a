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
import android.os.IBinder
import androidx.core.app.NotificationCompat
import java.util.concurrent.atomic.AtomicBoolean

class InternalAudioCaptionService : Service() {
    private var projection: MediaProjection? = null
    private var audioRecord: AudioRecord? = null
    private var engine: SenseVoiceEngine? = null
    private var captureThread: Thread? = null
    private val capturing = AtomicBoolean(false)

    override fun onBind(intent: Intent?): IBinder? = null
    override fun onCreate() { super.onCreate(); createNotificationChannel() }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when(intent?.action) {
            ACTION_STOP -> { stopCapture(); stopSelf() }
            ACTION_START -> {
                val code=intent.getIntExtra(EXTRA_RESULT_CODE,0)
                val data=if(Build.VERSION.SDK_INT>=33) intent.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java) else { @Suppress("DEPRECATION") intent.getParcelableExtra(EXTRA_RESULT_DATA) }
                if(code==0 || data==null){ sendStatus("未获得手机音频捕获权限"); stopSelf(); return START_NOT_STICKY }
                startAsForeground(); startCapture(code,data)
            }
        }
        return START_NOT_STICKY
    }

    private fun startAsForeground() {
        val n=NotificationCompat.Builder(this, CHANNEL_ID).setSmallIcon(android.R.drawable.ic_btn_speak_now).setContentTitle("Mandarin captions").setContentText("离线识别手机播放的中文音频").setOngoing(true).build()
        if(Build.VERSION.SDK_INT>=Build.VERSION_CODES.Q) startForeground(NOTIFICATION_ID,n,ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION) else startForeground(NOTIFICATION_ID,n)
    }

    private fun startCapture(resultCode:Int, resultData:Intent) {
        stopCapture(false)
        sendStatus("正在加载离线中文模型…")
        try { engine=SenseVoiceEngine(this) } catch(e:Throwable){ sendStatus("模型加载失败：${e.javaClass.simpleName}"); stopSelf(); return }
        val mgr=getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        projection=mgr.getMediaProjection(resultCode,resultData)?.also { it.registerCallback(object:MediaProjection.Callback(){ override fun onStop(){ stopCapture(); stopSelf() }},null) }
        val mp=projection ?: run { sendStatus("无法启动手机音频捕获"); stopSelf(); return }
        val cfg=AudioPlaybackCaptureConfiguration.Builder(mp).addMatchingUsage(AudioAttributes.USAGE_MEDIA).addMatchingUsage(AudioAttributes.USAGE_GAME).addMatchingUsage(AudioAttributes.USAGE_UNKNOWN).build()
        val fmt=AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(SenseVoiceEngine.SAMPLE_RATE).setChannelMask(AudioFormat.CHANNEL_IN_MONO).build()
        val min=AudioRecord.getMinBufferSize(SenseVoiceEngine.SAMPLE_RATE,AudioFormat.CHANNEL_IN_MONO,AudioFormat.ENCODING_PCM_16BIT).coerceAtLeast(4096)
        audioRecord=try { AudioRecord.Builder().setAudioFormat(fmt).setBufferSizeInBytes(min*2).setAudioPlaybackCaptureConfig(cfg).build() } catch(e:Throwable){ null }
        if(audioRecord?.state!=AudioRecord.STATE_INITIALIZED){ sendStatus("手机音频捕获没有初始化成功"); stopCapture(); stopSelf(); return }
        capturing.set(true); audioRecord?.startRecording()
        sendBroadcast(Intent(ACTION_INTERNAL_AUDIO_STATE).setPackage(packageName).putExtra(EXTRA_ACTIVE,true)); sendStatus("正在听手机音频…")
        captureThread=Thread {
            val chunkSamples=SenseVoiceEngine.SAMPLE_RATE*3; val buf=ShortArray(2048); val acc=ArrayList<Float>(chunkSamples)
            while(capturing.get()) {
                val n=audioRecord?.read(buf,0,buf.size) ?: -1
                if(n<=0) continue
                for(i in 0 until n) acc.add(buf[i]/32768f)
                if(acc.size>=chunkSamples){
                    val samples=FloatArray(chunkSamples){acc[it]}; acc.subList(0,chunkSamples).clear()
                    try { engine?.transcribe(samples)?.takeIf{it.isNotBlank()}?.let(::sendCaption) } catch(e:Throwable){ sendStatus("识别错误：${e.javaClass.simpleName}") }
                }
            }
        }.apply { name="SenseVoicePlayback"; start() }
    }

    private fun sendCaption(text:String){ sendBroadcast(Intent(ACTION_INTERNAL_CAPTION).setPackage(packageName).putExtra(EXTRA_TEXT,text).putExtra(EXTRA_FINAL,true)) }
    private fun sendStatus(text:String){ sendBroadcast(Intent(ACTION_INTERNAL_STATUS).setPackage(packageName).putExtra(EXTRA_TEXT,text)) }

    private fun stopCapture(sendStopped:Boolean=true){
        capturing.set(false); runCatching{audioRecord?.stop()}; audioRecord?.release(); audioRecord=null; captureThread?.interrupt(); captureThread=null
        engine?.release(); engine=null; runCatching{projection?.stop()}; projection=null
        if(sendStopped) sendBroadcast(Intent(ACTION_INTERNAL_AUDIO_STATE).setPackage(packageName).putExtra(EXTRA_ACTIVE,false))
    }
    override fun onDestroy(){ stopCapture(); super.onDestroy() }
    private fun createNotificationChannel(){ if(Build.VERSION.SDK_INT>=26)(getSystemService(NotificationManager::class.java)).createNotificationChannel(NotificationChannel(CHANNEL_ID,"Internal audio captions",NotificationManager.IMPORTANCE_LOW)) }

    companion object {
        const val ACTION_START="com.example.mandarincallcaptions.START_INTERNAL_AUDIO"; const val ACTION_STOP="com.example.mandarincallcaptions.STOP_INTERNAL_AUDIO"
        const val ACTION_INTERNAL_CAPTION="com.example.mandarincallcaptions.INTERNAL_CAPTION"; const val ACTION_INTERNAL_STATUS="com.example.mandarincallcaptions.INTERNAL_STATUS"; const val ACTION_INTERNAL_AUDIO_STATE="com.example.mandarincallcaptions.INTERNAL_STATE"
        const val EXTRA_RESULT_CODE="result_code"; const val EXTRA_RESULT_DATA="result_data"; const val EXTRA_TEXT="text"; const val EXTRA_FINAL="final"; const val EXTRA_ACTIVE="active"
        private const val CHANNEL_ID="internal_audio_captions"; private const val NOTIFICATION_ID=1402
    }
}
