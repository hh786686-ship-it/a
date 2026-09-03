package com.example.mandarincallcaptions

import android.Manifest
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.provider.Settings
import android.view.accessibility.AccessibilityManager
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.example.mandarincallcaptions.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    private val micPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) {
            refreshStatus()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.permissionButton.setOnClickListener {
            micPermission.launch(Manifest.permission.RECORD_AUDIO)
        }

        binding.accessibilityButton.setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
    }

    override fun onResume() {
        super.onResume()
        refreshStatus()
    }

    private fun refreshStatus() {
        val micOk = ContextCompat.checkSelfPermission(
            this, Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED

        val am = getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager
        val serviceOk = am.getEnabledAccessibilityServiceList(
            AccessibilityServiceInfo.FEEDBACK_ALL_MASK
        ).any { info ->
            info.resolveInfo.serviceInfo.packageName == packageName &&
                info.resolveInfo.serviceInfo.name.endsWith("CaptionAccessibilityService")
        }

        binding.statusText.text = when {
            micOk && serviceOk -> "Status: ready. Start a call, use speakerphone, and captions should appear."
            !micOk && !serviceOk -> "Status: allow microphone and enable the accessibility service."
            !micOk -> "Status: accessibility enabled; microphone permission still needed."
            else -> "Status: microphone allowed; enable the accessibility service."
        }
    }
}
