package com.example.chineseocr;

import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Rect;
import java.util.*;

/** Locate compact rows of dark ink missed by the initial OCR geometry.
 * Image-based candidate detection avoids relying only on existing OCR boxes.
 * Candidate regions still require the OCR recognizer to confirm actual text.
 */
final class SmallTextRegions {
  static ArrayList<Rect> uncovered(Bitmap bitmap, List<Rect> known, int limit) {
    ArrayList<Rect> result=new ArrayList<>();
    if(limit<=0)return result;
    final int width=bitmap.getWidth(),height=bitmap.getHeight(),stride=2;
    final int samples=(width+stride-1)/stride;
    ArrayList<Rect> candidates=new ArrayList<>();
    int start=-1,last=-1,minX=width,maxX=0,peak=0;
    for(int y=0;y<height;y+=stride){
      int hits=0,left=width,right=0;
      for(int x=0;x<width;x+=stride){
        int c=bitmap.getPixel(x,y);
        if(Color.red(c)<130&&Color.green(c)<130&&Color.blue(c)<130){
          hits++;left=Math.min(left,x);right=Math.max(right,x);
        }
      }
      boolean ink=hits>=Math.max(4,Math.min(10,samples/30))&&hits<samples*0.65;
      if(ink){
        if(start<0)start=y;
        last=y;minX=Math.min(minX,left);maxX=Math.max(maxX,right);
        peak=Math.max(peak,hits);
      }else if(start>=0){
        append(candidates,known,width,height,start,last,minX,maxX,peak);
        start=-1;minX=width;maxX=0;peak=0;
      }
    }
    if(start>=0)append(candidates,known,width,height,start,last,minX,maxX,peak);
    // Smaller lines first, then top-to-bottom. No more than limit OCR rescans.
    candidates.sort((a,b)->{
      int c=Integer.compare(a.height(),b.height());
      return c!=0?c:Integer.compare(a.top,b.top);
    });
    for(Rect c:candidates){
      boolean overlap=false;
      for(Rect chosen:result){
        Rect intersection=new Rect();
        if(intersection.setIntersect(c,chosen)&&intersection.width()*intersection.height()>c.width()*c.height()/3){
          overlap=true;break;
        }
      }
      if(!overlap)result.add(c);
      if(result.size()>=limit)break;
    }
    return result;
  }
  private static void append(List<Rect> result,List<Rect> known,int w,int h,int start,int last,int left,int right,int peak){
    int height=last-start+2;
    if(height<4||height>Math.max(42,h/25)||right-left<12||peak<4)return;
    Rect raw=new Rect(Math.max(0,left-6),Math.max(0,start-6),
       Math.min(w,right+8),Math.min(h,last+9));
    int covered=0;
    for(Rect box:known){
      if(box==null)continue;
      Rect intersection=new Rect();
      if(intersection.setIntersect(box,raw))covered+=intersection.width()*intersection.height();
    }
    if(covered>=raw.width()*raw.height()/2)return;
    // Reject full-width page rules, borders and huge illustration fragments.
    if(raw.width()>w*.95)return;
    result.add(raw);
  }
  private SmallTextRegions(){}
}
