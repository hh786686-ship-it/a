package com.example.minerunativeprobe;
import android.app.Activity;
import android.os.Bundle;
import android.widget.*;
public class NativeLoadActivity extends Activity {
  private TextView result;
  private boolean llamaLoaded = false, docvortexLoaded = false;
  private void tryLlama() {
    try {
      System.loadLibrary("ggml-base");
      System.loadLibrary("ggml-cpu");
      System.loadLibrary("ggml");
      System.loadLibrary("llama");
      llamaLoaded = true;
      result.setText("PASS: Android loaded the ARM64 MinerU-pinned llama.cpp native libraries. This does not test model inference.");
    } catch(Throwable error) {result.setText("FAIL: "+error.getClass().getSimpleName()+": "+error.getMessage());}
  }
  private void tryDocvortex() {
    try {
      System.loadLibrary("python3.11");
      System.loadLibrary("docvortex_native");
      docvortexLoaded = true;
      result.setText("PASS: Android dynamic linker loaded the ARM64 DocVortex native extension and Python runtime. This does not mean Python import or MinerU Standard parsing was tested.");
    } catch(Throwable error) {result.setText("FAIL: "+error.getClass().getSimpleName()+": "+error.getMessage());}
  }
  @Override public void onCreate(Bundle saved) {
    super.onCreate(saved);
    LinearLayout root = new LinearLayout(this);
    root.setOrientation(LinearLayout.VERTICAL);root.setPadding(30,40,30,30);
    TextView title=new TextView(this);title.setText("MinerU ARM64 native dependency probe");title.setTextSize(22);root.addView(title);
    result=new TextView(this);result.setText("Native libraries only. Not a full MinerU OCR app.");root.addView(result);
    Button llama=new Button(this);llama.setText("Test llama.cpp ARM64 loading");llama.setOnClickListener(v->tryLlama());root.addView(llama);
    Button rust=new Button(this);rust.setText("Test DocVortex + Python ARM64 loading");rust.setOnClickListener(v->tryDocvortex());root.addView(rust);
    setContentView(root);
  }
}
