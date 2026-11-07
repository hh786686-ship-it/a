package com.example.chineseocr;

import android.content.Context;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import ai.onnxruntime.*;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;
import java.io.*;
import java.nio.FloatBuffer;
import java.util.*;

@RunWith(AndroidJUnit4.class)
public class MineruOnnxAndroidSmokeTest {
  private File modelFile(Context ctx,String name)throws Exception{
    File dest=new File(ctx.getCacheDir(),name);
    try(InputStream in=ctx.getAssets().open(name);OutputStream out=new FileOutputStream(dest)){
      byte[] buffer=new byte[32768];int n;
      while((n=in.read(buffer))!=-1)out.write(buffer,0,n);
    }
    assertTrue(dest.length()>1000000);
    return dest;
  }
  private void runModel(String name,long[] shape)throws Exception{
    Context context=InstrumentationRegistry.getInstrumentation().getContext();
    File model=modelFile(context,name);
    OrtEnvironment env=OrtEnvironment.getEnvironment();
    try(OrtSession.SessionOptions options=new OrtSession.SessionOptions()){
      options.setIntraOpNumThreads(2);
      try(OrtSession session=env.createSession(model.getAbsolutePath(),options)){
        assertTrue(session.getInputNames().contains("x"));
        int cells=1;for(long dim:shape)cells=Math.multiplyExact(cells,(int)dim);
        float[] data=new float[cells];
        try(OnnxTensor tensor=OnnxTensor.createTensor(env,FloatBuffer.wrap(data),shape);
            OrtSession.Result results=session.run(Collections.singletonMap("x",tensor))){
          assertTrue(results.size()>0);
          assertNotNull(results.get(0).getValue());
        }
      }
    }
    android.util.Log.i("MINERU_ANDROID_SMOKE","PASS "+name+" model_bytes="+model.length());
  }
  @Test public void officialMineruDetectionOnnxRunsOnEmulator()throws Exception {
    runModel("mineru-det.onnx",new long[]{1,3,64,64});
  }
  @Test public void officialMineruRecognitionOnnxRunsOnEmulator()throws Exception {
    runModel("mineru-rec.onnx",new long[]{1,3,48,320});
  }
}
