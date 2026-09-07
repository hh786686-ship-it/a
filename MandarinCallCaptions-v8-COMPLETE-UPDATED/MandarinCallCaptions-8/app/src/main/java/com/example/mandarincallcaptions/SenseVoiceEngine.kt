package com.example.mandarincallcaptions

import android.content.Context
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineSenseVoiceModelConfig

class SenseVoiceEngine(context: Context) {
    private val recognizer: OfflineRecognizer

    init {
        val modelDir = MODEL_DIR
        val modelConfig = OfflineModelConfig(
            senseVoice = OfflineSenseVoiceModelConfig(
                model = "$modelDir/model.int8.onnx",
                language = "zh",
                useInverseTextNormalization = true
            ),
            tokens = "$modelDir/tokens.txt",
            numThreads = 2,
            debug = false,
            provider = "cpu"
        )
        recognizer = OfflineRecognizer(
            assetManager = context.assets,
            config = OfflineRecognizerConfig(modelConfig = modelConfig)
        )
    }

    @Synchronized
    fun transcribe(samples: FloatArray, sampleRate: Int = SAMPLE_RATE): String {
        if (samples.isEmpty()) return ""
        val stream = recognizer.createStream()
        return try {
            stream.acceptWaveform(samples, sampleRate = sampleRate)
            recognizer.decode(stream)
            recognizer.getResult(stream).text.trim()
        } finally {
            stream.release()
        }
    }

    fun release() = recognizer.release()

    companion object {
        const val SAMPLE_RATE = 16000
        const val MODEL_DIR = "sherpa-onnx-sense-voice-zh-en-ja-ko-yue-int8-2024-07-17"
    }
}
