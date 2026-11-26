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
import android.view.ScaleGestureDetector
import android.text.TextUtils
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.FrameLayout
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
        const val ACTION_WARM = "livechinese.warm"
        const val ACTION_MIC = "livechinese.mic"
        const val ACTION_IDLE = "livechinese.idle"
        const val CHANNEL = "livechinese_microphone"
        private const val ID = 46
        var onDismiss: (() -> Unit)? = null
        var instance: SubtitleOverlayService? = null
            private set
    }

    private lateinit var manager: WindowManager
    private val preferences by lazy { getSharedPreferences("caption_overlay", MODE_PRIVATE) }
    private var bubble: LinearLayout? = null
    private var params: WindowManager.LayoutParams? = null
    private var captionsBox: LinearLayout? = null
    private var alphaControls: LinearLayout? = null
    private var toolbarControls: LinearLayout? = null
    private var lastLines: List<String> = emptyList()
    private var visibleRows = 5
    private var labels: List<TextView> = emptyList()
    private var fontSize = 23f
    private var overlayOpacity = 0.82f
    private var widthDp = 340
    private var heightDp = 345
    private var leftPx = -1
    private var topPx = -1

    override fun onBind(intent: Intent?): IBinder? = null

    private lateinit var note: Notification
    private var micActive = false

    override fun onCreate() {
        super.onCreate()
        instance = this
        manager = getSystemService(WINDOW_SERVICE) as WindowManager
        fontSize = preferences.getFloat("font", 23f).coerceIn(8f, 40f)
        overlayOpacity = preferences.getFloat("opacity", 0.82f).coerceIn(0.15f, 1f)
        widthDp = preferences.getInt("width", 340).coerceIn(100, 1200)
        heightDp = preferences.getInt("height", 345).coerceIn(minHeightDp(), 1400)
        leftPx = preferences.getInt("x", -1)
        topPx = preferences.getInt("y", -1)

        val notifications = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        notifications.createNotificationChannel(NotificationChannel(
            CHANNEL, "Live Chinese microphone", NotificationManager.IMPORTANCE_LOW))
        val openIntent = Intent(this, MainActivity::class.java)
        val pending = PendingIntent.getActivity(this, 0, openIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        note = Notification.Builder(this, CHANNEL)
            .setContentTitle("Live Chinese subtitles")
            .setContentText("Keeping the speech model ready")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentIntent(pending)
            .setOngoing(true)
            .build()
        setMicActive(false)
    }

    fun setMicActive(active: Boolean) {
        micActive = active
        if (Build.VERSION.SDK_INT >= 34) {
            val type = ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE or
                (if (active) ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE else 0)
            startForeground(ID, note, type)
        } else if (Build.VERSION.SDK_INT >= 29 && active) {
            startForeground(ID, note, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } else {
            startForeground(ID, note)
        }
    }

    fun hideOverlay() {
        saveSettings()
        val view = bubble
        bubble = null
        if (view != null) try { manager.removeView(view) } catch (_: Exception) {}
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_WARM -> return START_NOT_STICKY
            ACTION_MIC -> { setMicActive(true); return START_NOT_STICKY }
            ACTION_IDLE -> { setMicActive(false); return START_NOT_STICKY }
        }
        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "Enable Display over other apps first.", Toast.LENGTH_LONG).show()
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
            setPadding(dp(2), 0, dp(2), 0)
            gravity = Gravity.CENTER_VERTICAL
        }

    private fun minHeightDp(): Int =
        (fontSize * resources.displayMetrics.scaledDensity / density * 1.35f + 4f)
            .roundToInt().coerceAtLeast(20)

    private fun maxWidthDp(): Int = (resources.displayMetrics.widthPixels / density).toInt()
        .coerceAtLeast(100)
    private fun maxHeightDp(): Int = (resources.displayMetrics.heightPixels / density).toInt()
        .coerceAtLeast(minHeightDp())

    private fun resizeWindow(newWidthDp: Int, newHeightDp: Int) {
        val p = params ?: return
        val view = bubble ?: return
        widthDp = newWidthDp.coerceIn(100, maxWidthDp())
        heightDp = newHeightDp.coerceIn(minHeightDp(), maxHeightDp())
        p.width = dp(widthDp)
        p.height = dp(heightDp)
        p.x = p.x.coerceIn(0, (resources.displayMetrics.widthPixels - p.width).coerceAtLeast(0))
        p.y = p.y.coerceIn(0, (resources.displayMetrics.heightPixels - p.height).coerceAtLeast(0))
        adaptLayout()
        manager.updateViewLayout(view, p)
    }

    private fun showOverlay() {
        widthDp = widthDp.coerceIn(100, maxWidthDp())
        heightDp = heightDp.coerceIn(minHeightDp(), maxHeightDp())

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.argb((overlayOpacity * 255).roundToInt(), 70, 70, 70))
            setPadding(dp(2), dp(1), dp(2), dp(1))
        }
        val toolbar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val drag = text("⠿", 16f).apply {
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.CYAN)
        }
        drag.gravity = Gravity.CENTER
        drag.contentDescription = "Move overlay"

        fun action(title: String, block: () -> Unit) {
            val v = text(title, 17f).apply {
                gravity = Gravity.CENTER
                setOnClickListener { block() }
            }
            v.setPadding(0, 0, 0, 0)
            toolbar.addView(v, LinearLayout.LayoutParams(0, dp(24), 1f))
        }
        action("−") {
            fontSize = (fontSize - 2f).coerceAtLeast(8f)
            adaptLayout()
            saveSettings()
        }
        action("+") {
            fontSize = (fontSize + 2f).coerceAtMost(40f)
            adaptLayout()
            saveSettings()
        }
        action("◐") {
            overlayOpacity = if (overlayOpacity > 0.5f) 0.28f else 0.82f
            root.setBackgroundColor(Color.argb((overlayOpacity * 255).roundToInt(), 70, 70, 70))
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
                        (overlayOpacity * 255).roundToInt(), 70, 70, 70))
                }
                override fun onStartTrackingTouch(bar: SeekBar?) {}
                override fun onStopTrackingTouch(bar: SeekBar?) { saveSettings() }
            })
        }
        alphaRow.addView(alpha, LinearLayout.LayoutParams(0, dp(31), 1f))

        action("×") { onDismiss?.invoke(); hideOverlay() }
        root.addView(toolbar, LinearLayout.LayoutParams(-1, dp(24)))
        toolbarControls = toolbar
        root.addView(alphaRow, LinearLayout.LayoutParams(-1, dp(31)))
        alphaControls = alphaRow

        val body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        captionsBox = body
        labels = (0 until 5).map {
            text("", fontSize).also { view ->
                view.maxLines = 1
                view.includeFontPadding = false
                view.ellipsize = TextUtils.TruncateAt.START
                view.setTextIsSelectable(false)
                body.addView(view, LinearLayout.LayoutParams(-1, 0, 1f))
            }
        }
        val content = FrameLayout(this)
        content.addView(body, FrameLayout.LayoutParams(-1, -1).apply { rightMargin = dp(32) })
        root.addView(content, LinearLayout.LayoutParams(-1, 0, 1f))

        val sizeLabel = text("⇲", 16f).apply {
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
            setTextColor(Color.CYAN)
        }
        sizeLabel.contentDescription = "Drag corner to resize width and height"
        sizeLabel.setPadding(0, 0, dp(2), 0)
        content.addView(drag, FrameLayout.LayoutParams(dp(15), -1, Gravity.RIGHT or Gravity.TOP).apply {
            rightMargin = dp(16)
        })
        content.addView(sizeLabel, FrameLayout.LayoutParams(dp(17), dp(20), Gravity.RIGHT or Gravity.BOTTOM))
        drag.setPadding(0, 0, 0, 0)
        sizeLabel.setPadding(0, 0, 0, 0)

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
                    val p = params
                    if (event.actionMasked == MotionEvent.ACTION_UP && p != null && event.rawY - downY > dp(48) && event.rawY > resources.displayMetrics.heightPixels - dp(80)) {
                        onDismiss?.invoke()
                        hideOverlay()
                    } else saveSettings()
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

        val pinch = ScaleGestureDetector(this, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                fontSize = (fontSize * detector.scaleFactor).coerceIn(8f, 40f)
                resizeWindow(widthDp, heightDp)
                return true
            }
        })
        body.setOnTouchListener { _, event ->
            pinch.onTouchEvent(event)
            if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) saveSettings()
            true
        }
        bubble = root
        adaptLayout()
        manager.addView(root, params)
    }

    private fun adaptLayout() {
        val showToolbar = heightDp >= minHeightDp() * 3 + 24
        toolbarControls?.visibility = if (showToolbar) View.VISIBLE else View.GONE
        val showAlpha = showToolbar && heightDp >= 200 && widthDp >= 220
        alphaControls?.visibility = if (showAlpha) View.VISIBLE else View.GONE
        val bodyHeight = (heightDp - 2 - (if (showToolbar) 24 else 0) - (if (showAlpha) 31 else 0)).coerceAtLeast(1)
        val fontHeightDp = fontSize * resources.displayMetrics.scaledDensity / density
        visibleRows = if (heightDp <= minHeightDp() + 2) 1 else (bodyHeight / (fontHeightDp * 1.35f + 2f)).toInt().coerceIn(1, 5)
        val fitFont = ((bodyHeight.toFloat() / visibleRows - 2f) / 1.25f *
            density / resources.displayMetrics.scaledDensity).coerceAtLeast(8f)
        labels.forEachIndexed { index, view ->
            view.visibility = if (index < visibleRows) View.VISIBLE else View.GONE
            view.textSize = fontSize.coerceAtMost(fitFont)
        }
        renderLines()
    }

    private fun renderLines() {
        val recent = lastLines.takeLast(visibleRows)
        labels.forEachIndexed { index, view ->
            view.text = recent.getOrNull(index) ?: ""
        }
    }

    fun setLines(lines: List<String>) {
        val five = lines.takeLast(5)
        android.os.Handler(mainLooper).post {
            lastLines = five
            renderLines()
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
