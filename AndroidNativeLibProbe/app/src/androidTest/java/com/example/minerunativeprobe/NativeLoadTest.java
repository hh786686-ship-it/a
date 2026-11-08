package com.example.minerunativeprobe;
import org.junit.Test;
import org.junit.runner.RunWith;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import static org.junit.Assert.*;
@RunWith(AndroidJUnit4.class)
public class NativeLoadTest {
  @Test public void mineruPinnedLlamaCppArm64CanLoadAtRuntime() {
    // This verifies Android's dynamic linker, not actual VLM inference.
    System.loadLibrary("ggml-base");
    System.loadLibrary("ggml-cpu");
    System.loadLibrary("ggml");
    System.loadLibrary("llama");
    assertTrue(true);
  }
}
