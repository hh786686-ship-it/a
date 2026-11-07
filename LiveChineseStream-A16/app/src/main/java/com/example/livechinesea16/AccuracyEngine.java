package com.example.livechinesea16;

import android.content.Context;
import com.k2fsa.sherpa.onnx.OfflinePunctuation;
import com.k2fsa.sherpa.onnx.OfflinePunctuationConfig;
import com.k2fsa.sherpa.onnx.OfflinePunctuationModelConfig;
import java.util.regex.Pattern;

/** Offline neural punctuation only. No second ASR model or network usage. */
public final class AccuracyEngine {
    private final OfflinePunctuation punctuator;
    private static final Pattern CJK = Pattern.compile("[\\p{IsHan}]");
    private static final Pattern TRAILING = Pattern.compile("[。！？.!?]+$");

    public AccuracyEngine(Context context) {
        OfflinePunctuationModelConfig pm = new OfflinePunctuationModelConfig();
        pm.setCtTransformer("punctuation/model.int8.onnx");
        pm.setNumThreads(1);
        pm.setProvider("cpu");
        punctuator = new OfflinePunctuation(context.getAssets(), new OfflinePunctuationConfig(pm));
    }

    public String restorePunctuation(String text) {
        String clean = sanitize(text);
        if (clean.isEmpty()) return "";
        String result = punctuator.addPunctuation(clean);
        return ensureSentenceEnd(result == null || result.trim().isEmpty() ? clean : result);
    }

    private static String sanitize(String text) {
        if (text == null) return "";
        String s = text.replaceAll("\\s+", " ").trim();
        s = s.replaceAll("(?<=[\\p{IsHan}，。！？、]) +(?=[\\p{IsHan}，。！？、])", "");
        return s.replaceAll(" +([，。！？、])", "$1");
    }

    public static String ensureSentenceEnd(String text) {
        String s = sanitize(text);
        if (s.isEmpty()) return "";
        if (TRAILING.matcher(s).find()) return s;
        return s + (CJK.matcher(s).find() ? "。" : ".");
    }

    public void release() { punctuator.release(); }
}
