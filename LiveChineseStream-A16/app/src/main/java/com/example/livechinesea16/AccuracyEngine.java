package com.example.livechinesea16;

import android.content.Context;

import com.k2fsa.sherpa.onnx.FeatureConfig;
import com.k2fsa.sherpa.onnx.OfflineModelConfig;
import com.k2fsa.sherpa.onnx.OfflineRecognizer;
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig;
import com.k2fsa.sherpa.onnx.OfflineSenseVoiceModelConfig;
import com.k2fsa.sherpa.onnx.OfflineStream;
import com.k2fsa.sherpa.onnx.OfflinePunctuation;
import com.k2fsa.sherpa.onnx.OfflinePunctuationConfig;
import com.k2fsa.sherpa.onnx.OfflinePunctuationModelConfig;

import java.util.regex.Pattern;

/**
 * Second-pass speech recognizer and sentence punctuation.
 * Called only from the single background correction thread.
 * Both models are installed in the APK; no network/API is used.
 */
public final class AccuracyEngine {
    private final OfflineRecognizer offline;
    private final OfflinePunctuation punctuator;
    private static final Pattern TAGS = Pattern.compile("<\\|[^|>]+\\|>");
    private static final Pattern CJK = Pattern.compile("[\\p{IsHan}]");
    private static final Pattern TRAILING_MARKS = Pattern.compile("[。！？.!?]+$");

    public AccuracyEngine(Context context) {
        OfflineSenseVoiceModelConfig sv = new OfflineSenseVoiceModelConfig();
        sv.setModel("secondpass/model.int8.onnx");
        // Empty language means automatic Mandarin / English selection.
        sv.setLanguage("");
        sv.setUseInverseTextNormalization(true);
        OfflineModelConfig m = new OfflineModelConfig();
        m.setSenseVoice(sv);
        m.setTokens("secondpass/tokens.txt");
        m.setNumThreads(2);
        m.setProvider("cpu");
        OfflineRecognizerConfig config = new OfflineRecognizerConfig();
        config.setFeatConfig(new FeatureConfig(16000, 80, 0.0f));
        config.setModelConfig(m);
        offline = new OfflineRecognizer(context.getAssets(), config);

        OfflinePunctuationModelConfig pm = new OfflinePunctuationModelConfig();
        pm.setCtTransformer("punctuation/model.int8.onnx");
        pm.setNumThreads(1);
        pm.setProvider("cpu");
        OfflinePunctuationConfig pc = new OfflinePunctuationConfig(pm);
        punctuator = new OfflinePunctuation(context.getAssets(), pc);
    }

    public String recognize(float[] samples) {
        if (samples == null || samples.length < 1600) return "";
        OfflineStream stream = offline.createStream();
        try {
            stream.acceptWaveform(samples, 16000);
            offline.decode(stream);
            String raw = sanitize(offline.getResult(stream).getText());
            if (!isSupportedText(raw)) return "";
            return restorePunctuation(raw);
        } finally {
            stream.release();
        }
    }

    /**
     * SenseVoice is multilingual internally, but the app accepts correction text
     * only in Mandarin (Han characters) or English (Latin characters).
     * This guards against Korean/Japanese hallucinations on silence or noise.
     */
    public static boolean isSupportedText(String text) {
        String s = sanitize(text);
        if (s.isEmpty()) return false;
        boolean hasWords = false;
        for (int offset = 0; offset < s.length();) {
            int cp = s.codePointAt(offset);
            offset += Character.charCount(cp);
            if (!Character.isLetter(cp)) continue;
            Character.UnicodeScript script = Character.UnicodeScript.of(cp);
            if (script != Character.UnicodeScript.HAN
                    && script != Character.UnicodeScript.LATIN) return false;
            hasWords = true;
        }
        return hasWords;
    }

    public static boolean isPlausibleCorrection(String provisional, String corrected) {
        String oldText = sanitize(provisional);
        String newText = sanitize(corrected);
        if (!isSupportedText(newText)) return false;
        if (oldText.isEmpty()) return false;
        // Don't turn short Chinese phrases into unrelated English or vice versa.
        boolean oldHan = CJK.matcher(oldText).find();
        boolean newHan = CJK.matcher(newText).find();
        if (oldHan != newHan) return false;
        // Reject grossly different lengths from noisy/silent second-pass output.
        int before = oldText.codePointCount(0, oldText.length());
        int after = newText.codePointCount(0, newText.length());
        return after >= Math.max(1, (int)Math.ceil(before * 0.35))
                && after <= Math.max(3, (int)Math.ceil(before * 2.5));
    }

    public String restorePunctuation(String text) {
        String cleaned = sanitize(text);
        if (cleaned.isEmpty()) return "";
        String result = punctuator.addPunctuation(cleaned);
        return ensureSentenceEnd(sanitize(result == null || result.trim().isEmpty() ? cleaned : result));
    }

    static String sanitize(String text) {
        if (text == null) return "";
        String s = TAGS.matcher(text).replaceAll("");
        // Collapse whitespace without merging normal English words.
        s = s.replaceAll("\\s+", " ").trim();
        // Restore normal Chinese spacing.
        s = s.replaceAll("(?<=[\\p{IsHan}，。！？、]) +(?=[\\p{IsHan}，。！？、])", "");
        s = s.replaceAll(" +([，。！？、])", "$1");
        return s;
    }

    public static String ensureSentenceEnd(String text) {
        String s = sanitize(text);
        if (s.isEmpty()) return "";
        // Preserve natural question/exclamation marks and English periods.
        if (TRAILING_MARKS.matcher(s).find()) return s;
        // User requested Chinese full stops between sentences.
        if (CJK.matcher(s).find()) return s + "。";
        return s + ".";
    }

    public void release() {
        punctuator.release();
        offline.release();
    }
}
