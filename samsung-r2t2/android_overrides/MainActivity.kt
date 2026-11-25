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
        MethodChannel(flutterEngine.dartExecutor.binaryMessenger, channelName)
            .setMethodCallHandler { call, result ->
                when (call.method) {
                    "prepareBundledModel" -> {
                        val path = call.argument<String>("path")
                        if (path == null) {
                            result.error("MODEL", "Missing model path", null)
                        } else {
                            Thread {
                                try {
                                    val target = java.io.File(path)
                                    val expected = assets.open("offline-model/size.txt").bufferedReader().use { it.readText().trim().toLong() }
                                    val marker = java.io.File(path + ".bundled")
                                    if (!target.isFile || target.length() != expected || !marker.isFile) {
                                        target.parentFile?.mkdirs()
                                        val partial = java.io.File(path + ".extracting")
                                        if (partial.exists()) partial.delete()
                                        if (target.parentFile!!.usableSpace < expected + 32L * 1024 * 1024)
                                            throw java.io.IOException("Not enough free storage to unpack offline model")
                                        assets.open("offline-model/confucius4-r2t2-q4_k.gguf").use { input ->
                                            partial.outputStream().buffered(1024 * 1024).use { output ->
                                                input.copyTo(output, 1024 * 1024)
                                            }
                                        }
                                        if (partial.length() != expected) throw java.io.IOException("Incomplete model extraction")
                                        if (target.exists() && !target.delete()) throw java.io.IOException("Cannot replace old model")
                                        if (!partial.renameTo(target)) throw java.io.IOException("Cannot install model")
                                        marker.writeText(expected.toString())
                                    }
                                    runOnUiThread { result.success(null) }
                                } catch (e: Exception) {
                                    runOnUiThread { result.error("MODEL", e.message, null) }
                                }
                            }.start()
                        }
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
