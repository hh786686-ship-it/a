package com.example.chineseocr;

import ai.onnxruntime.*;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Rect;
import java.io.*;
import java.nio.FloatBuffer;
import java.util.*;

/**
 * Experimental ONNX port of the OCR detection/recognition models distributed by
 * opendatalab/MinerU-4_models_onnx. NOT a port of the complete MinerU 4 parser.
 * All inference runs locally. Never silently substitutes ML Kit.
 */
final class MineruOnnxOcr implements AutoCloseable {
  static final class TextRegion {
    final Rect box;
    final String text;
    TextRegion(Rect box, String text){this.box=box;this.text=text;}
  }
  private final OrtEnvironment environment;
  private final OrtSession detector, recognizer;
  private final String[] alphabet;

  MineruOnnxOcr(Context context)throws Exception{
    environment=OrtEnvironment.getEnvironment();
    OrtSession.SessionOptions opts=new OrtSession.SessionOptions();
    opts.setIntraOpNumThreads(2);
    try {
      detector=environment.createSession(copyAsset(context,"mineru-det.onnx").getAbsolutePath(),opts);
      recognizer=environment.createSession(copyAsset(context,"mineru-rec.onnx").getAbsolutePath(),opts);
    }finally{opts.close();}
    List<String> glyphs=new ArrayList<>();
    glyphs.add(""); // CTC blank, model class 0
    try(BufferedReader reader=new BufferedReader(new InputStreamReader(
        context.getAssets().open("ppocrv6_dict.txt"),java.nio.charset.StandardCharsets.UTF_8))){
      String line;while((line=reader.readLine())!=null)glyphs.add(line);
    }
    glyphs.add(" "); // PaddleOCR's use_space_char=true
    alphabet=glyphs.toArray(new String[0]);
    if(alphabet.length!=18710)throw new IOException(
      "Incorrect OCR dictionary: expected 18710 classes, have "+alphabet.length);
  }

  private static File copyAsset(Context ctx,String name)throws IOException{
    File target=new File(ctx.getCacheDir(),name);
    try(InputStream in=ctx.getAssets().open(name);OutputStream out=new FileOutputStream(target)){
      byte[] buf=new byte[65536];int n;
      while((n=in.read(buf))!=-1)out.write(buf,0,n);
    }
    return target;
  }
  private static int round32(int x){return Math.max(32,((x+31)/32)*32);}
  private static float[] pixels(Bitmap bmp,int width,int height, boolean recognition) {
    Bitmap scaled=Bitmap.createScaledBitmap(bmp,width,height,true);
    int[] colors=new int[width*height];
    scaled.getPixels(colors,0,width,0,0,width,height);
    float[] tensor=new float[3*width*height];
    int area=width*height;
    for(int i=0;i<area;i++){
      int color=colors[i];
      float blue=Color.blue(color)/255f,green=Color.green(color)/255f,red=Color.red(color)/255f;
      if(recognition){
        tensor[i]=(blue-.5f)/.5f;
        tensor[area+i]=(green-.5f)/.5f;
        tensor[2*area+i]=(red-.5f)/.5f;
      }else{
        // Official PP-OCRv6 Tiny detection model preprocessing: BGR ImageNet stats.
        tensor[i]=(blue-.485f)/.229f;
        tensor[area+i]=(green-.456f)/.224f;
        tensor[2*area+i]=(red-.406f)/.225f;
      }
    }
    if(scaled!=bmp)scaled.recycle();
    return tensor;
  }
  private static float[][] probabilityMap(Object value)throws IOException{
    if(value instanceof float[][][][])return ((float[][][][])value)[0][0];
    if(value instanceof float[][][])return ((float[][][])value)[0];
    throw new IOException("Unexpected MinerU detector output: "+value.getClass());
  }
  /** Lightweight connected-component approximation to PaddleOCR DB postprocess. */
  private static List<Rect> components(float[][] probabilities,int scaleX,int scaleY,
                                         int originalWidth,int originalHeight) {
    int h=probabilities.length,w=probabilities[0].length;
    boolean[][] used=new boolean[h][w];
    int[] queue=new int[h*w];
    List<Rect> result=new ArrayList<>();
    for(int y=1;y<h-1;y++)for(int x=1;x<w-1;x++){
      if(used[y][x]||probabilities[y][x]<.30f)continue;
      int head=0,tail=0;
      queue[tail++]=y*w+x;used[y][x]=true;
      int left=x,right=x,top=y,bottom=y,pixels=0;
      while(head<tail){
        int p=queue[head++],py=p/w,px=p%w;
        pixels++;left=Math.min(left,px);right=Math.max(right,px);
        top=Math.min(top,py);bottom=Math.max(bottom,py);
        for(int yy=Math.max(0,py-1);yy<=Math.min(h-1,py+1);yy++)
        for(int xx=Math.max(0,px-1);xx<=Math.min(w-1,px+1);xx++){
          if(!used[yy][xx]&&probabilities[yy][xx]>=.30f){
            used[yy][xx]=true;queue[tail++]=yy*w+xx;
          }
        }
      }
      if(pixels<8||right-left<3||bottom-top<3)continue;
      // Expand by ~one-third text height as a conservative DB-style approximation.
      int pad=Math.max(3,(bottom-top)/4);
      Rect rect=new Rect(
        Math.max(0,(left-pad)*originalWidth/w),
        Math.max(0,(top-pad)*originalHeight/h),
        Math.min(originalWidth,(right+pad+1)*originalWidth/w),
        Math.min(originalHeight,(bottom+pad+1)*originalHeight/h));
      if(rect.width()>10&&rect.height()>8)result.add(rect);
    }
    return result;
  }
  private static List<Rect> mergeNearby(List<Rect> boxes) {
    List<Rect> sorted=new ArrayList<>(boxes);
    sorted.sort(Comparator.comparingInt((Rect r)->r.top).thenComparingInt(r->r.left));
    List<Rect> merged=new ArrayList<>();
    for(Rect rect:sorted){
      boolean found=false;
      for(Rect candidate:merged){
        int top=Math.max(rect.top,candidate.top);
        int bottom=Math.min(rect.bottom,candidate.bottom);
        int overlap=Math.max(0,bottom-top);
        int gap=Math.max(0,Math.max(rect.left-candidate.right,candidate.left-rect.right));
        int minHeight=Math.max(1,Math.min(candidate.height(),rect.height()));
        if(overlap>minHeight*.6&&gap<Math.max(12,minHeight*.85)){
          candidate.union(rect);found=true;break;
        }
      }
      if(!found)merged.add(new Rect(rect));
    }
    merged.sort(Comparator.comparingInt((Rect r)->r.top).thenComparingInt(r->r.left));
    return merged;
  }
  private String recognizeRegion(Bitmap page,Rect bounds)throws Exception{
    Rect r=new Rect(Math.max(0,bounds.left),Math.max(0,bounds.top),
        Math.min(page.getWidth(),bounds.right),Math.min(page.getHeight(),bounds.bottom));
    if(r.width()<4||r.height()<4)return "";
    Bitmap crop=Bitmap.createBitmap(page,r.left,r.top,r.width(),r.height());
    try{
      int width=Math.min(1280,round32(Math.round(48f*r.width()/r.height())));
      width=Math.max(32,width);
      float[] input=pixels(crop,width,48,true);
      try(OnnxTensor tensor=OnnxTensor.createTensor(environment,FloatBuffer.wrap(input),new long[]{1,3,48,width});
          OrtSession.Result prediction=recognizer.run(Collections.singletonMap("x",tensor))){
        Object value=prediction.get(0).getValue();
        if(!(value instanceof float[][][]))throw new IOException("Unexpected recognition output shape");
        float[][] timesteps=((float[][][])value)[0];
        StringBuilder text=new StringBuilder();
        int previous=-1;
        for(float[] step:timesteps){
          int best=0;
          for(int i=1;i<step.length;i++)if(step[i]>step[best])best=i;
          if(best!=0&&best!=previous&&best<alphabet.length)text.append(alphabet[best]);
          previous=best;
        }
        return text.toString();
      }
    }finally{crop.recycle();}
  }

  List<TextRegion> recognize(Bitmap source)throws Exception{
    int maxSide=1280;
    float ratio=Math.min(1f,(float)maxSide/Math.max(source.getWidth(),source.getHeight()));
    int w=round32(Math.round(source.getWidth()*ratio));
    int h=round32(Math.round(source.getHeight()*ratio));
    float[] input=pixels(source,w,h,false);
    List<Rect> regions;
    try(OnnxTensor tensor=OnnxTensor.createTensor(environment,FloatBuffer.wrap(input),new long[]{1,3,h,w});
        OrtSession.Result output=detector.run(Collections.singletonMap("x",tensor))){
      regions=mergeNearby(components(probabilityMap(output.get(0).getValue()),w,h,
        source.getWidth(),source.getHeight()));
    }
    List<TextRegion> texts=new ArrayList<>();
    for(Rect box:regions){
      String content=recognizeRegion(source,box).trim();
      if(!content.isEmpty())texts.add(new TextRegion(box,content));
    }
    texts.sort(Comparator.comparingInt((TextRegion r)->r.box.top).thenComparingInt(r->r.box.left));
    return texts;
  }
  @Override public void close()throws Exception{recognizer.close();detector.close();}
}
