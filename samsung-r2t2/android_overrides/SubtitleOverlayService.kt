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
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import kotlin.math.roundToInt

/** Movable, corner-resizable, five-line caption window.
 * Preferences persist width, height, position, font size and opacity between runs.
 */
class SubtitleOverlayService : Service() {
    companion object {
        const val ACTION_SHOW = "livechinese.show"
        const val CHANNEL = "livechinese_microphone"
        private const val ID = 46
        var instance: SubtitleOverlayService? = null
            private set
    }

    private lateinit var manager: WindowManager
    private val preferences by lazy { getSharedPreferences("caption_overlay", MODE_PRIVATE) }
    private var bubble: LinearLayout? = null
    private var params: WindowManager.LayoutParams? = null
    private var captionsBox: LinearLayout? = null
    private var labels: List<TextView> = emptyList()
    private var fontSize = 23f
    private var overlayOpacity = 0.82f
    private var widthDp = 340
    private var heightDp = 345
    private var leftPx = -1
    private var topPx = -1

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        manager = getSystemService(WINDOW_SERVICE) as WindowManager
        fontSize = preferences.getFloat("font", 23f).coerceIn(14f, 40f)
        overlayOpacity = preferences.getFloat("opacity", 0.82f).coerceIn(0.15f, 1f)
        widthDp = preferences.getInt("width", 340).coerceIn(220, 1200)
        heightDp = preferences.getInt("height", 345).coerceIn(250, 1400)
        leftPx = preferences.getInt("x", -1)
        topPx = preferences.getInt("y", -1)

        val notifications = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        notifications.createNotificationChannel(NotificationChannel(
            CHANNEL, "Live Chinese microphone", NotificationManager.IMPORTANCE_LOW))
        val openIntent = Intent(this, MainActivity::class.java)
        val pending = PendingIntent.getActivity(this, 0, openIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val note = Notification.Builder(this, CHANNEL)
            .setContentTitle("Live Chinese subtitles")
            .setContentText("Floating captions are enabled")
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
            Toast.makeText(this, "Enable Display over other apps first.", Toast.LENGTH_LONG).show()
            stopSelf()
            return START_NOT_STICKY
        }
        if (bubble == null) showOverlay()
        return START_NOT_STICKY
    }

    private val density: Float get() = resources.displayMetrics.density
    private fun dp(value: Int): Int = (value * density).roundToInt()
    private fun pxToDp(value: Int): Int = (value / density).roundToInt()

    private fun saveSettings() {
        val p = params
        preferences.edit()
            .putInt("width", widthDp)
            .putInt("height", heightDp)
            .putFloat("font", fontSize)
            .putFloat("opacity", overlayOpacity)
            .putInt("x", p?.x ?: leftPx)
            .putInt("y", p?.y ?: topPx)
            .apply()
    }

    private fun text(label: String, size: Float = 18f): TextView =
        TextView(this).apply {
            text = label
            setTextColor(Color.WHITE)
            textSize = size
            setPadding(dp(5), dp(2), dp(5), dp(2))
            gravity = Gravity.CENTER_VERTICAL
        }

    private fun maxWidthDp(): Int = (resources.displayMetrics.widthPixels / density).toInt()
        .coerceAtLeast(220)
    private fun maxHeightDp(): Int = (resources.displayMetrics.heightPixels / density).toInt()
        .coerceAtLeast(250)

    private fun resizeWindow(newWidthDp: Int, newHeightDp: Int) {
        val p = params ?: return
        val view = bubble ?: return
        widthDp = newWidthDp.coerceIn(220, maxWidthDp())
        heightDp = newHeightDp.coerceIn(250, maxHeightDp())
        p.width = dp(widthDp)
        p.height = dp(heightDp)
        manager.updateViewLayout(view, p)
    }

    private fun showOverlay() {
        widthDp = widthDp.coerceIn(220, maxWidthDp())
        heightDp = heightDp.coerceIn(250, maxHeightDp())

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.argb((overlayOpacity * 255).roundToInt(), 13, 19, 31))
            setPadding(dp(5), dp(2), dp(5), dp(2))
        }
        val toolbar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val drag = text("☰ Move", 14f).apply {
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.CYAN)
        }
        toolbar.addView(drag, LinearLayout.LayoutParams(0, dp(38), 1f))

        fun action(title: String, block: () -> Unit) {
            val v = text(title, 17f).apply {
                gravity = Gravity.CENTER
                setOnClickListener { block() }
            }
            toolbar.addView(v, LinearLayout.LayoutParams(dp(31), dp(38)))
        }
        action("−") {
            fontSize = (fontSize - 2f).coerceAtLeast(14f)
            labels.forEach { it.textSize = fontSize }
            saveSettings()
        }
        action("+") {
            fontSize = (fontSize + 2f).coerceAtMost(40f)
            labels.forEach { it.textSize = fontSize }
            saveSettings()
        }
        action("↔") {
            resizeWindow(if (widthDp >= maxWidthDp() - 20) 260 else widthDp + 35, heightDp)
            saveSettings()
        }
        action("↕") {
            resizeWindow(widthDp, if (heightDp >= maxHeightDp() - 40) 280 else heightDp + 45)
            saveSettings()
        }

        val alphaRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        alphaRow.addView(text("Opacity", 12f), LinearLayout.LayoutParams(dp(63), dp(30)))
        val alpha = SeekBar(this).apply {
            max = 85
            progress = ((overlayOpacity - 0.15f) * 100f).roundToInt().coerceIn(0, 85)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(bar: SeekBar?, value: Int, fromUser: Boolean) {
                    if (!fromUser) return
                    overlayOpacity = (0.15f + value / 100f).coerceIn(0.15f, 1f)
                    root.setBackgroundColor(Color.argb(
                        (overlayOpacity * 255).roundToInt(), 13, 19, 31))
                }
                override fun onStartTrackingTouch(bar: SeekBar?) {}
                override fun onStopTrackingTouch(bar: SeekBar?) { saveSettings() }
            })
        }
        alphaRow.addView(alpha, LinearLayout.LayoutParams(0, dp(31), 1f))

        action("×") { stopSelf() }
        root.addView(toolbar, LinearLayout.LayoutParams(-1, dp(38)))
        root.addView(alphaRow, LinearLayout.LayoutParams(-1, dp(35)))

        val body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        captionsBox = body
        labels = (0 until 5).map {
            text("", fontSize).also { view ->
                view.maxLines = 1
                view.setTextIsSelectable(false)
                body.addView(view, LinearLayout.LayoutParams(-1, 0, 1f))
            }
        }
        root.addView(body, LinearLayout.LayoutParams(-1, 0, 1f))

        val resizeRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
        }
        val sizeLabel = text("Drag corner to resize  ⇲", 12f).apply {
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
            setTextColor(Color.CYAN)
        }
        resizeRow.addView(sizeLabel, LinearLayout.LayoutParams(-2, dp(34)))
        root.addView(resizeRow, LinearLayout.LayoutParams(-1, dp(34)))

        params = WindowManager.LayoutParams(
            dp(widthDp), dp(heightDp),
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = (if (leftPx >= 0) leftPx else dp(15))
                .coerceIn(0, (resources.displayMetrics.widthPixels - dp(widthDp)).coerceAtLeast(0))
            y = (if (topPx >= 0) topPx else dp(90))
                .coerceIn(0, (resources.displayMetrics.heightPixels - dp(heightDp)).coerceAtLeast(0))
        }

        var downX = 0f
        var downY = 0f
        var startX = 0
        var startY = 0
        drag.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    startX = params?.x ?: 0
                    startY = params?.y ?: 0
                    downX = event.rawX
                    downY = event.rawY
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val p = params ?: return@setOnTouchListener false
                    p.x = (startX + (event.rawX - downX).roundToInt())
                        .coerceIn(0, (resources.displayMetrics.widthPixels - p.width).coerceAtLeast(0))
                    p.y = (startY + (event.rawY - downY).roundToInt())
                        .coerceIn(0, (resources.displayMetrics.heightPixels - p.height).coerceAtLeast(0))
                    manager.updateViewLayout(root, p)
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    saveSettings()
                    true
                }
                else -> false
            }
        }

        var downWidth = 0
        var downHeight = 0
        sizeLabel.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.rawX
                    downY = event.rawY
                    downWidth = widthDp
                    downHeight = heightDp
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val deltaWidth = pxToDp((event.rawX - downX).roundToInt())
                    val deltaHeight = pxToDp((event.rawY - downY).roundToInt())
                    resizeWindow(downWidth + deltaWidth, downHeight + deltaHeight)
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    saveSettings()
                    true
                }
                else -> false
            }
        }

        bubble = root
        manager.addView(root, params)
    }

    fun setLines(lines: List<String>) {
        val five = lines.takeLast(5)
        android.os.Handler(mainLooper).post {
            labels.forEachIndexed { index, view -> view.text = five.getOrNull(index) ?: "" }
        }
    }

    override fun onDestroy() {
        saveSettings()
        val view = bubble
        bubble = null
        if (view != null) try { manager.removeView(view) } catch (_: Exception) {}
        instance = null
        super.onDestroy()
    }
}
