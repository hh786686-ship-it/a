package org.livechinese.r2t2_live_chinese

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import kotlin.math.roundToInt

class SubtitleOverlayService : Service() {
    companion object {
        const val ACTION_SHOW = "livechinese.show"
        const val CHANNEL = "livechinese_microphone"
        private const val ID = 46
        var instance: SubtitleOverlayService? = null
            private set
    }

    private lateinit var manager: WindowManager
    private var bubble: LinearLayout? = null
    private var params: WindowManager.LayoutParams? = null
    private var labels: List<TextView> = emptyList()
    private var fontSize = 23f
    private var overlayOpacity = 0.82f
    private var widthDp = 340

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        manager = getSystemService(WINDOW_SERVICE) as WindowManager
        val notifications = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        notifications.createNotificationChannel(NotificationChannel(
            CHANNEL, "Live Chinese microphone", NotificationManager.IMPORTANCE_LOW))
        val openIntent = Intent(this, MainActivity::class.java)
        val pending = PendingIntent.getActivity(this, 0, openIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val note = Notification.Builder(this, CHANNEL)
            .setContentTitle("Live Chinese subtitles")
            .setContentText("Microphone transcription companion is active")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentIntent(pending)
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(ID, note, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } else {
            startForeground(ID, note)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "Overlay permission is required.", Toast.LENGTH_LONG).show()
            stopSelf()
            return START_NOT_STICKY
        }
        if (bubble == null) showOverlay()
        return START_NOT_STICKY
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).roundToInt()

    private fun text(label: String, size: Float = 18f): TextView =
        TextView(this).apply {
            this.text = label
            setTextColor(Color.WHITE)
            textSize = size
            setPadding(dp(5), dp(3), dp(5), dp(3))
            gravity = Gravity.CENTER_VERTICAL
        }

    private fun showOverlay() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.argb((overlayOpacity * 255).roundToInt(), 13, 19, 31))
            setPadding(dp(5), dp(3), dp(5), dp(5))
        }
        val toolbar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val drag = text("☰  Live Chinese", 14f).apply {
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.CYAN)
        }
        toolbar.addView(drag, LinearLayout.LayoutParams(0, dp(39), 1f))

        fun action(title: String, block: () -> Unit) {
            val v = text(title, 18f)
            v.setOnClickListener { block() }
            toolbar.addView(v, LinearLayout.LayoutParams(dp(36), dp(39)))
        }
        action("−") { fontSize = (fontSize - 2f).coerceAtLeast(14f); labels.forEach { it.textSize = fontSize } }
        action("+") { fontSize = (fontSize + 2f).coerceAtMost(40f); labels.forEach { it.textSize = fontSize } }
        action("↔") {
            widthDp = if (widthDp >= 390) 260 else widthDp + 40
            params?.let { p ->
                p.width = dp(widthDp)
                bubble?.let { manager.updateViewLayout(it, p) }
            }
        }
        action("◐") {
            overlayOpacity = if (overlayOpacity > 0.5f) 0.28f else 0.82f
            root.setBackgroundColor(Color.argb((overlayOpacity * 255).roundToInt(), 13, 19, 31))
        }
        action("×") { stopSelf() }

        root.addView(toolbar)
        labels = (0 until 5).map {
            text("", fontSize).also { view ->
                view.maxLines = 1
                view.minHeight = dp(40)
                view.setTextIsSelectable(false)
                root.addView(view, LinearLayout.LayoutParams(-1, dp(44)))
            }
        }
        params = WindowManager.LayoutParams(
            dp(widthDp), WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = dp(15); y = dp(90)
        }
        var startX = 0
        var startY = 0
        var touchX = 0f
        var touchY = 0f
        drag.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    startX = params!!.x; startY = params!!.y
                    touchX = event.rawX; touchY = event.rawY
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val p = params ?: return@setOnTouchListener false
                    p.x = startX + (event.rawX - touchX).roundToInt()
                    p.y = startY + (event.rawY - touchY).roundToInt()
                    manager.updateViewLayout(root, p)
                    true
                }
                else -> true
            }
        }
        bubble = root
        manager.addView(root, params)
    }

    fun setLines(lines: List<String>) {
        val five = lines.takeLast(5)
        android.os.Handler(mainLooper).post {
            labels.forEachIndexed { index, view ->
                view.text = five.getOrNull(index) ?: ""
            }
        }
    }

    override fun onDestroy() {
        val view = bubble
        bubble = null
        if (view != null) try { manager.removeView(view) } catch (_: Exception) {}
        instance = null
        super.onDestroy()
    }
}
