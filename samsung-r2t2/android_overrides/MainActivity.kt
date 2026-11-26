package org.livechinese.r2t2_live_chinese

import android.Manifest
import android.app.Activity
import android.content.Context
import io.flutter.embedding.engine.FlutterEngineCache
import io.flutter.embedding.engine.dart.DartExecutor
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
    override fun provideFlutterEngine(context: Context): FlutterEngine {
        val cache = FlutterEngineCache.getInstance()
        cache.get("livechinese-retained")?.let { return it }
        val engine = FlutterEngine(context.applicationContext)
        engine.dartExecutor.executeDartEntrypoint(DartExecutor.DartEntrypoint.createDefault())
        cache.put("livechinese-retained", engine)
        return engine
    }
    override fun shouldDestroyEngineWithHost(): Boolean = false
    private var transferResult: MethodChannel.Result? = null
    private var transferPath: String? = null
    private var exporting = false
    @Deprecated("Activity result API")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != 6051) return
        val reply = transferResult ?: return
        val path = transferPath ?: return
        val isExport = exporting
        transferResult = null
        transferPath = null
        val uri = data?.data
        if (resultCode != Activity.RESULT_OK || uri == null) { reply.success(false); return }
        val resolver = applicationContext.contentResolver
        Thread {
            try {
                val file = java.io.File(path)
                if (isExport) {
                    val output = resolver.openOutputStream(uri, "w") ?: error("Cannot open destination")
                    output.use { out -> file.inputStream().use { it.copyTo(out, 1024 * 1024) } }
                } else {
                    val input = resolver.openInputStream(uri) ?: error("Cannot open model")
                    input.use { source -> file.outputStream().use { out ->
                        val buffer = ByteArray(1024 * 1024)
                        var total = 0L
                        while (true) {
                            val n = source.read(buffer)
                            if (n < 0) break
                            total += n
                            require(total <= 1490915264L) { "Model file too large" }
                            out.write(buffer, 0, n)
                        }
                        require(total == 1490915264L) { "Incorrect model size" }
                    } }
                }
                runOnUiThread { reply.success(true) }
            } catch (e: Exception) {
                if (!isExport) java.io.File(path).delete()
                runOnUiThread { reply.error("TRANSFER", e.message, null) }
            }
        }.start()
    }
    private val channelName = "livechinese/overlay"
    override fun configureFlutterEngine(flutterEngine: FlutterEngine) {
        super.configureFlutterEngine(flutterEngine)
        val channel = MethodChannel(flutterEngine.dartExecutor.binaryMessenger, channelName)
        SubtitleOverlayService.onDismiss = { channel.invokeMethod("overlayDismissed", null) }
        channel.setMethodCallHandler { call, result ->
                when (call.method) {
                    "importModel", "exportModel" -> {
                        if (transferResult != null) { result.error("BUSY", "Transfer already active", null) }
                        else {
                            val path = call.argument<String>("path")
                            if (path == null) result.error("PATH", "Missing model path", null)
                            else try {
                                exporting = call.method == "exportModel"
                                val picker = Intent(if (exporting) Intent.ACTION_CREATE_DOCUMENT else Intent.ACTION_OPEN_DOCUMENT)
                                picker.addCategory(Intent.CATEGORY_OPENABLE)
                                picker.type = if (exporting) "application/octet-stream" else "*/*"
                                if (exporting) picker.putExtra(Intent.EXTRA_TITLE, "confucius4-r2t2-q4_k.gguf")
                                transferResult = result
                                transferPath = path
                                startActivityForResult(picker, 6051)
                            } catch (e: Exception) {
                                transferResult = null; transferPath = null
                                result.error("PICKER", e.message, null)
                            }
                        }
                    }
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
                    "keepModelWarm", "startMicService", "stopMicService" -> {
                        try {
                            val intent = Intent(this, SubtitleOverlayService::class.java)
                            intent.action = when (call.method) {
                                "startMicService" -> SubtitleOverlayService.ACTION_MIC
                                "stopMicService" -> SubtitleOverlayService.ACTION_IDLE
                                else -> SubtitleOverlayService.ACTION_WARM
                            }
                            if (call.method == "stopMicService" && SubtitleOverlayService.instance != null) {
                                SubtitleOverlayService.instance?.setMicActive(false)
                            } else if (call.method != "stopMicService") {
                                androidx.core.content.ContextCompat.startForegroundService(this, intent)
                            }
                            result.success(null)
                        } catch (e: Exception) { result.error("SERVICE", e.message, null) }
                    }
                    "stopOverlay" -> { 
                        SubtitleOverlayService.instance?.hideOverlay()
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
                    else -> result.notImplemented()
                }
            }
    }
}
