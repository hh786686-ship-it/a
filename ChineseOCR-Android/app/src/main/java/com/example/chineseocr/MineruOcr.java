package com.example.chineseocr;

import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Rect;
import ai.onnxruntime.*;
import java.io.*;
import java.nio.FloatBuffer;
import java.util.*;

/**
 * Offline MinerU 4 PP-OCRv6 Tiny Detection + Small Recognition ONNX OCR.
 * These are two genuine ONNX models shipped by MinerU, not MinerU's full pipeline.
 * Lightweight CPU implementation of PaddleOCR-style preprocess / DB mask / CTC decode.
 */
final class MineruOcr implements AutoCloseable {
  interface Progress { void update(String message); }
  static final class Line {
    final Rect bounds;
    final String text;
    final double confidence;
    Line(Rect r,String s,double c){bounds=r;text=s;confidence=c;}
  }
  private final OrtEnvironment env;
  private final OrtSession detector,recognizer;
  private final ArrayList<String> alphabet=new ArrayList<>();
  private static final int TILE=960,MAX_BOXES=200;
  private static final float[] MEAN={0.485f,0.456f,0.406f},STD={0.229f,0.224f,0.225f};
  MineruOcr(File modelDetector,File modelRecognizer,InputStream dictionary)throws Exception {
    env=OrtEnvironment.getEnvironment();
    try(OrtSession.SessionOptions opts=new OrtSession.SessionOptions()){
      opts.setIntraOpNumThreads(2);
      detector=env.createSession(modelDetector.getAbsolutePath(),opts);
      recognizer=env.createSession(modelRecognizer.getAbsolutePath(),opts);
    }
    try(BufferedReader reader=new BufferedReader(new InputStreamReader(dictionary,"UTF-8"))){
      String line;while((line=reader.readLine())!=null)alphabet.add(line);
    }
    alphabet.add(" "); // PaddleOCR Global.use_space_char=true
    if(alphabet.size()+1!=18710)throw new IOException("Wrong PP-OCRv6 vocabulary size: "+alphabet.size());
  }

  private static int round32(int n){return Math.max(32,((n+31)/32)*32);}
  private static int clamp(int n,int min,int max){return Math.max(min,Math.min(max,n));}
  private static Bitmap resized(Bitmap b,int width,int height){
    if(b.getWidth()==width&&b.getHeight()==height)return b;
    return Bitmap.createScaledBitmap(b,width,height,true);
  }
  private static float[] chw(Bitmap bitmap,int w,int h,boolean detection){
    int[] pixels=new int[w*h];
    bitmap.getPixels(pixels,0,w,0,0,w,h);
    float[] tensor=new float[w*h*3], plane=w*h;
    for(int i=0;i<pixels.length;i++){
      int argb=pixels[i],r=Color.red(argb),g=Color.green(argb),b=Color.blue(argb);
      float[] c={r/255.0f,g/255.0f,b/255.0f};
      for(int ch=0;ch<3;ch++)
        tensor[ch*w*h+i]=detection?(c[ch]-MEAN[ch])/STD[ch]:(c[ch]-0.5f)/0.5f;
    }
    return tensor;
  }

  /** Connected components of the DB detector mask, with small-gap morphological bridging. */
  private static ArrayList<Rect> components(float[][] scores,int height,int width){
    int n=height*width;
    byte[] mask=new byte[n];
    final float threshold=0.27f;
    for(int y=0;y<height;y++)for(int x=0;x<width;x++){
      if(scores[y][x]>threshold)mask[y*width+x]=1;
    }
    // Gentle horizontal closing joins letters from the same line.
    byte[] joined=new byte[n];
    for(int y=0;y<height;y++)for(int x=0;x<width;x++)if(mask[y*width+x]!=0){
      int ix=y*width+x;
      for(int dx=-3;dx<=3;dx++){
        int xx=x+dx;if(xx>=0&&xx<width)joined[y*width+xx]=1;
      }
    }
    byte[] seen=new byte[n];
    int[] queue=new int[n];
    ArrayList<Rect> result=new ArrayList<>();
    for(int pos=0;pos<n;pos++){
      if(joined[pos]==0||seen[pos]!=0)continue;
      int head=0,tail=0;queue[tail++]=pos;seen[pos]=1;
      int minX=width,minY=height,maxX=0,maxY=0,original=0;
      while(head<tail){
        int p=queue[head++],x=p%width,y=p/width;
        minX=Math.min(minX,x);maxX=Math.max(maxX,x);
        minY=Math.min(minY,y);maxY=Math.max(maxY,y);
        if(mask[p]!=0)original++;
        if(x>0&&seen[p-1]==0&&joined[p-1]!=0){seen[p-1]=1;queue[tail++]=p-1;}
        if(x+1<width&&seen[p+1]==0&&joined[p+1]!=0){seen[p+1]=1;queue[tail++]=p+1;}
        if(y>0&&seen[p-width]==0&&joined[p-width]!=0){seen[p-width]=1;queue[tail++]=p-width;}
        if(y+1<height&&seen[p+width]==0&&joined[p+width]!=0){seen[p+width]=1;queue[tail++]=p+width;}
      }
      int boxW=maxX-minX+1,boxH=maxY-minY+1;
      if(boxH>=7&&boxW>=9&&original>=30){
        int padX=Math.max(2,(int)(boxH*0.08)),padY=Math.max(2,(int)(boxH*0.10));
        result.add(new Rect(clamp(minX-padX,0,width),clamp(minY-padY,0,height),
          clamp(maxX+padX+1,0,width),clamp(maxY+padY+1,0,height)));
      }
    }
    result.sort((a,b)->{
      int dy=a.top-b.top;
      if(Math.abs(dy)<Math.max(8,Math.min(a.height(),b.height())/2))return a.left-b.left;
      return dy;
    });
    if(result.size()>MAX_BOXES)return new ArrayList<>(result.subList(0,MAX_BOXES));
    return result;
  }

  private ArrayList<Rect> detect(Bitmap b)throws Exception {
    // Respect 32-pixel stride and avoid huge memory allocation.
    final int origW=b.getWidth(),origH=b.getHeight();
    float shrink=Math.min(1.0f,TILE/(float)Math.max(origW,origH));
    int w=round32(Math.max(32,(int)(origW*shrink))),h=round32(Math.max(32,(int)(origH*shrink)));
    Bitmap scaled=resized(b,w,h);
    float[] input=chw(scaled,w,h,true);
    ArrayList<Rect> boxes;
    try(OnnxTensor tensor=OnnxTensor.createTensor(env,FloatBuffer.wrap(input),new long[]{1,3,h,w});
        OrtSession.Result output=detector.run(Collections.singletonMap("x",tensor))){
      float[][][][] logits=(float[][][][])output.get(0).getValue();
      float[][] scores=logits[0][0];
      ArrayList<Rect> modelBoxes=components(scores,scores.length,scores[0].length);
      boxes=new ArrayList<>(modelBoxes.size());
      float sx=origW/(float)scores[0].length,sy=origH/(float)scores.length;
      for(Rect box:modelBoxes){
        boxes.add(new Rect(clamp(Math.round(box.left*sx),0,origW),
           clamp(Math.round(box.top*sy),0,origH),
           clamp(Math.round(box.right*sx),0,origW),
           clamp(Math.round(box.bottom*sy),0,origH)));
      }
    }
    if(scaled!=b)scaled.recycle();
    return boxes;
  }

  private Line recognise(Bitmap page,Rect rect)throws Exception {
    if(rect.width()<2||rect.height()<2)return new Line(rect,"",0);
    Bitmap crop=Bitmap.createBitmap(page,rect.left,rect.top,rect.width(),rect.height());
    // RecResizeImg: ratio-preserving resize to height 48 and pad to width multiple of 32.
    int width=clamp(round32(Math.round(48f*crop.getWidth()/crop.getHeight())),32,960);
    Bitmap fixed=resized(crop,width,48);
    float[] data=chw(fixed,width,48,false);
    float[][][] outputValues;
    try(OnnxTensor in=OnnxTensor.createTensor(env,FloatBuffer.wrap(data),new long[]{1,3,48,width});
        OrtSession.Result out=recognizer.run(Collections.singletonMap("x",in))){
      outputValues=(float[][][])out.get(0).getValue();
    }
    if(fixed!=crop)fixed.recycle();crop.recycle();
    StringBuilder text=new StringBuilder();
    int prev=0,count=0;double conf=0;
    for(float[] step:outputValues[0]){
      int best=0;float highest=Float.NEGATIVE_INFINITY;
      for(int j=0;j<step.length;j++)if(step[j]>highest){highest=step[j];best=j;}
      if(best!=0&&best!=prev&&best<=alphabet.size()){
        text.append(alphabet.get(best-1));
        conf+=highest;count++;
      }
      prev=best;
    }
    return new Line(rect,text.toString(),count>0?conf/count:0);
  }

  private static boolean duplicate(Rect a,Rect b){
    int left=Math.max(a.left,b.left),top=Math.max(a.top,b.top);
    int overlap=Math.max(0,Math.min(a.right,b.right)-left)*Math.max(0,Math.min(a.bottom,b.bottom)-top);
    int smaller=Math.min(a.width()*a.height(),b.width()*b.height());
    return smaller>0&&overlap/(double)smaller>=0.55;
  }

  ArrayList<Line> read(Bitmap page,Progress progress)throws Exception {
    ArrayList<Rect> regions=new ArrayList<>();
    int width=page.getWidth(),height=page.getHeight();
    // Full-page pass with tile passes for small Chinese text; overlap avoids split lines.
    progress.update("MinerU: detecting page layout");
    regions.addAll(detect(page));
    int tileH=Math.min(height,Math.max(450,height/2));
    int stride=Math.max(180,(int)(tileH*0.70));
    if(height>1200){
      for(int top=0;top<height;top+=stride){
        int bottom=Math.min(height,top+tileH),y=Math.max(0,bottom-tileH);
        Bitmap tile=Bitmap.createBitmap(page,0,y,width,bottom-y);
        ArrayList<Rect> cropped=detect(tile);
        for(Rect r:cropped){r.offset(0,y);regions.add(r);}
        tile.recycle();
        if(bottom>=height)break;
      }
    }
    regions.sort((a,b)->Integer.compare(b.width()*b.height(),a.width()*a.height()));
    ArrayList<Rect> unique=new ArrayList<>();
    for(Rect box:regions){
      boolean same=false;
      for(Rect prior:unique)if(duplicate(box,prior)){same=true;break;}
      if(!same&&box.width()>=4&&box.height()>=4)unique.add(box);
    }
    unique.sort((a,b)->{
      if(Math.abs(a.top-b.top)<Math.max(12,Math.min(a.height(),b.height())/2))return a.left-b.left;
      return a.top-b.top;
    });
    ArrayList<Line> lines=new ArrayList<>();
    for(int i=0;i<unique.size();i++){
      progress.update("MinerU: recognizing "+(i+1)+"/"+unique.size()+" lines");
      Line line=recognise(page,unique.get(i));
      if(line.text!=null&&!line.text.trim().isEmpty())lines.add(line);
    }
    return lines;
  }

  @Override public void close()throws Exception {recognizer.close();detector.close();}
}
