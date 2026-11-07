package com.example.livechinesea16;

import android.content.Context;
import com.k2fsa.sherpa.onnx.FeatureConfig;
import com.k2fsa.sherpa.onnx.OfflineModelConfig;
import com.k2fsa.sherpa.onnx.OfflineQwen3AsrModelConfig;
import com.k2fsa.sherpa.onnx.OfflineRecognizer;
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig;
import com.k2fsa.sherpa.onnx.OfflineStream;

/**
 * Offline Qwen3-ASR 0.6B INT8 rescoring for completed speech segments.
 * Live transcript is never delayed by Qwen inference.
 */
public final class QwenCorrectionEngine {
    private final OfflineRecognizer recognizer;

    public QwenCorrectionEngine(Context context) {
        OfflineQwen3AsrModelConfig q = new OfflineQwen3AsrModelConfig();
        q.setConvFrontend("qwen/conv_frontend.onnx");
        q.setEncoder("qwen/encoder.int8.onnx");
        q.setDecoder("qwen/decoder.int8.onnx");
        q.setTokenizer("qwen/tokenizer");
        q.setMaxTotalLen(512);
        q.setMaxNewTokens(128);
        OfflineModelConfig model = new OfflineModelConfig();
        model.setQwen3Asr(q);
        model.setNumThreads(2);
        model.setProvider("cpu");
        OfflineRecognizerConfig config = new OfflineRecognizerConfig();
        config.setFeatConfig(new FeatureConfig(16000, 80, 0.0f));
        config.setModelConfig(model);
        recognizer = new OfflineRecognizer(context.getAssets(), config);
    }

    public String recognize(float[] speech) {
        if (speech == null || speech.length < 1600) return "";
        OfflineStream stream = recognizer.createStream();
        try {
            stream.acceptWaveform(speech, 16000);
            recognizer.decode(stream);
            String value = recognizer.getResult(stream).getText();
            return value == null ? "" : value.trim();
        } finally {
            stream.release();
        }
    }

    public static boolean isPlausibleCorrection(String original, String correction) {
        if (original == null || correction == null) return false;
        String oldText = original.trim();
        String newText = correction.trim();
        if (oldText.isEmpty() || newText.isEmpty()) return false;
        boolean oldChinese = oldText.codePoints().anyMatch(cp -> Character.UnicodeScript.of(cp) == Character.UnicodeScript.HAN);
        boolean newChinese = false;
        for (int cp : newText.codePoints().toArray()) {
            if (!Character.isLetter(cp)) continue;
            Character.UnicodeScript script = Character.UnicodeScript.of(cp);
            if (script != Character.UnicodeScript.HAN && script != Character.UnicodeScript.LATIN) return false;
            if (script == Character.UnicodeScript.HAN) newChinese = true;
        }
        if (oldChinese != newChinese) return false;
        int a = oldText.codePointCount(0, oldText.length());
        int b = newText.codePointCount(0, newText.length());
        return b >= Math.max(1, (int)Math.ceil(a * 0.3))
                && b <= Math.max(6, (int)Math.ceil(a * 2.8));
    }

    public void release() { recognizer.release(); }
}
