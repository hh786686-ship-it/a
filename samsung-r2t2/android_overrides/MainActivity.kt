package org.livechinese.r2t2_live_chinese

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.content.pm.PackageManager
import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.MethodChannel

class MainActivity : FlutterActivity() {
    private val channelName = "livechinese/overlay"
    override fun configureFlutterEngine(flutterEngine: FlutterEngine) {
        super.configureFlutterEngine(flutterEngine)
        val channel = MethodChannel(flutterEngine.dartExecutor.binaryMessenger, channelName)
        SubtitleOverlayService.onDismiss = { channel.invokeMethod("overlayDismissed", null) }
        channel.setMethodCallHandler { call, result ->
                when (call.method) {
                    "availableStorage" -> {
                        val path = call.argument<String>("path")
                        if (path == null) result.error("STORAGE", "Missing path", null)
                        else try { result.success(android.os.StatFs(path).availableBytes) }
                        catch (e: Exception) { result.error("STORAGE", e.message, null) }
                    }
                    "hasOverlayPermission" -> result.success(Settings.canDrawOverlays(this))
                    "requestOverlayPermission" -> {
                        val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                            Uri.parse("package:$packageName"))
                        startActivity(intent)
                        result.success(null)
                    }
                    "startOverlay" -> {
                        if (!Settings.canDrawOverlays(this)) {
                            result.error("NO_OVERLAY", "Enable Display over other apps for Live Chinese.", null)
                        } else {
                            try {
                                val intent = Intent(this, SubtitleOverlayService::class.java)
                                intent.action = SubtitleOverlayService.ACTION_SHOW
                                androidx.core.content.ContextCompat.startForegroundService(this, intent)
                                result.success(null)
                            } catch (e: Exception) {
                                result.error("SERVICE", e.message, null)
                            }
                        }
                    }
                    "setCaptions" -> {
                        val lines = call.argument<List<String>>("lines") ?: emptyList()
                        SubtitleOverlayService.instance?.setLines(lines)
                        result.success(null)
                    }
                    "stopOverlay" -> {
                        stopService(Intent(this, SubtitleOverlayService::class.java))
                        result.success(null)
                    }
                    else -> result.notImplemented()
                }
            }
    }
}
