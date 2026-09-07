package com.example.mandarincallcaptions

import android.Manifest
import android.accessibilityservice.AccessibilityServiceInfo
import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.accessibility.AccessibilityManager
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.example.mandarincallcaptions.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val prefs by lazy { getSharedPreferences(PREFS_NAME, MODE_PRIVATE) }
    private var receiverRegistered = false

    private val internalAudioReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                InternalAudioCaptionService.ACTION_INTERNAL_AUDIO_STATE -> {
                    val active = intent.getBooleanExtra(InternalAudioCaptionService.EXTRA_ACTIVE, false)
                    binding.internalAudioStatus.text = if (active) {
                        "Phone audio captions: active — play a Mandarin video now"
                    } else {
                        "Phone audio captions: off"
                    }
                }
                InternalAudioCaptionService.ACTION_INTERNAL_STATUS -> {
                    val text = intent.getStringExtra(InternalAudioCaptionService.EXTRA_TEXT)
                    if (!text.isNullOrBlank()) binding.internalAudioStatus.text = "Phone audio: $text"
                }
            }
        }
    }

    private val micPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            prefs.edit().putBoolean(KEY_ASKED_MIC, true).apply()
            if (!granted) {
                binding.statusText.text = "Microphone permission was not granted. Tap the button again to open Android app settings."
            }
            refreshStatus()
        }

    private val mediaProjectionPermission =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == Activity.RESULT_OK && result.data != null) {
                val serviceIntent = Intent(this, InternalAudioCaptionService::class.java).apply {
                    action = InternalAudioCaptionService.ACTION_START
                    putExtra(InternalAudioCaptionService.EXTRA_RESULT_CODE, result.resultCode)
                    putExtra(InternalAudioCaptionService.EXTRA_RESULT_DATA, result.data)
                }
                ContextCompat.startForegroundService(this, serviceIntent)
                binding.internalAudioStatus.text = "Phone audio captions: starting…"
            } else {
                binding.internalAudioStatus.text = "Phone audio captions: Android capture permission was not granted"
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.permissionButton.setOnClickListener {
            val granted = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
            if (granted) {
                binding.statusText.text = "Microphone permission is already allowed ✓"
                return@setOnClickListener
            }

            val askedBefore = prefs.getBoolean(KEY_ASKED_MIC, false)
            if (askedBefore && !shouldShowRequestPermissionRationale(Manifest.permission.RECORD_AUDIO)) {
                openAppSettings()
            } else {
                micPermission.launch(Manifest.permission.RECORD_AUDIO)
            }
        }

        binding.accessibilityButton.setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }

        binding.startInternalAudioButton.setOnClickListener {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                binding.internalAudioStatus.text = "Phone audio captions need Android 13 or newer."
                return@setOnClickListener
            }
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                binding.internalAudioStatus.text = "Microphone/audio permission is required first. Tap ‘Allow microphone’."
                micPermission.launch(Manifest.permission.RECORD_AUDIO)
                return@setOnClickListener
            }

            val am = getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager
            val overlayEnabled = am.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK).any { info ->
                info.resolveInfo.serviceInfo.packageName == packageName &&
                    info.resolveInfo.serviceInfo.name.endsWith("CaptionAccessibilityService")
            }
            if (!overlayEnabled) {
                binding.internalAudioStatus.text = "Enable the caption accessibility service first so captions can appear on top of videos."
                return@setOnClickListener
            }

            val manager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            mediaProjectionPermission.launch(manager.createScreenCaptureIntent())
        }

        binding.stopInternalAudioButton.setOnClickListener {
            startService(Intent(this, InternalAudioCaptionService::class.java).apply {
                action = InternalAudioCaptionService.ACTION_STOP
            })
            binding.internalAudioStatus.text = "Phone audio captions: stopped"
        }

        val width = prefs.getInt(KEY_WIDTH_PERCENT, 90)
        val height = prefs.getInt(KEY_HEIGHT_PERCENT, 25)
        val font = prefs.getInt(KEY_FONT_SP, 24)
        val lines = prefs.getInt(KEY_MAX_LINES, 4)

        binding.widthSlider.value = width.toFloat()
        binding.heightSlider.value = height.toFloat()
        binding.fontSlider.value = font.toFloat()
        binding.linesSlider.value = lines.toFloat()
        updateLabels(width, height, font, lines)

        binding.widthSlider.addOnChangeListener { _, value, _ ->
            prefs.edit().putInt(KEY_WIDTH_PERCENT, value.toInt()).apply()
            updateLabels(value.toInt(), binding.heightSlider.value.toInt(), binding.fontSlider.value.toInt(), binding.linesSlider.value.toInt())
        }
        binding.heightSlider.addOnChangeListener { _, value, _ ->
            prefs.edit().putInt(KEY_HEIGHT_PERCENT, value.toInt()).apply()
            updateLabels(binding.widthSlider.value.toInt(), value.toInt(), binding.fontSlider.value.toInt(), binding.linesSlider.value.toInt())
        }
        binding.fontSlider.addOnChangeListener { _, value, _ ->
            prefs.edit().putInt(KEY_FONT_SP, value.toInt()).apply()
            updateLabels(binding.widthSlider.value.toInt(), binding.heightSlider.value.toInt(), value.toInt(), binding.linesSlider.value.toInt())
        }
        binding.linesSlider.addOnChangeListener { _, value, _ ->
            prefs.edit().putInt(KEY_MAX_LINES, value.toInt()).apply()
            updateLabels(binding.widthSlider.value.toInt(), binding.heightSlider.value.toInt(), binding.fontSlider.value.toInt(), value.toInt())
        }
    }

    override fun onStart() {
        super.onStart()
        if (!receiverRegistered) {
            val filter = IntentFilter().apply {
                addAction(InternalAudioCaptionService.ACTION_INTERNAL_AUDIO_STATE)
                addAction(InternalAudioCaptionService.ACTION_INTERNAL_STATUS)
            }
            if (Build.VERSION.SDK_INT >= 33) registerReceiver(internalAudioReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
            else {
                @Suppress("DEPRECATION")
                registerReceiver(internalAudioReceiver, filter)
            }
            receiverRegistered = true
        }
    }

    override fun onStop() {
        if (receiverRegistered) {
            runCatching { unregisterReceiver(internalAudioReceiver) }
            receiverRegistered = false
        }
        super.onStop()
    }

    override fun onResume() {
        super.onResume()
        refreshStatus()
    }

    private fun openAppSettings() {
        startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.parse("package:$packageName")
        })
    }

    private fun updateLabels(width: Int, height: Int, font: Int, lines: Int) {
        binding.widthLabel.text = "Window width: $width%"
        binding.heightLabel.text = "Window height: $height%"
        binding.fontLabel.text = "Caption text size: $font"
        binding.linesLabel.text = "Visible caption lines: $lines"
    }

    private fun refreshStatus() {
        val micOk = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        val am = getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager
        val serviceOk = am.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK).any { info ->
            info.resolveInfo.serviceInfo.packageName == packageName &&
                info.resolveInfo.serviceInfo.name.endsWith("CaptionAccessibilityService")
        }

        binding.permissionButton.text = if (micOk) "1. Microphone allowed ✓" else "1. Allow microphone"
        binding.statusText.text = when {
            micOk && serviceOk -> "Status: ready for microphone/speakerphone captions."
            !micOk && !serviceOk -> "Status: allow microphone and enable the accessibility service."
            !micOk -> "Status: accessibility enabled; microphone permission still needed."
            else -> "Status: microphone allowed; enable the accessibility service."
        }
    }

    companion object {
        const val PREFS_NAME = "caption_settings"
        const val KEY_WIDTH_PERCENT = "width_percent"
        const val KEY_HEIGHT_PERCENT = "height_percent"
        const val KEY_FONT_SP = "font_sp"
        const val KEY_MAX_LINES = "max_lines"
        const val KEY_ASKED_MIC = "asked_mic"
    }
}
