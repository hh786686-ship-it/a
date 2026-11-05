package com.example.chineseocr;

import java.util.Locale;

/** Pure-Java pixel heuristic for image-only text. Does not claim certainty. */
public final class PixelWordStyle {
  public interface Pixels {
    int width();
    int height();
    int argb(int x,int y);
  }
  private PixelWordStyle(){}
  private static int red(int c){return (c>>>16)&255;}
  private static int green(int c){return (c>>>8)&255;}
  private static int blue(int c){return c&255;}
  public static WordStyles.Style estimate(Pixels pixels,int x1,int y1,int x2,int y2){
    if(pixels==null||x2<=x1||y2<=y1)return WordStyles.Style.normal();
    int left=Math.max(0,x1),right=Math.min(pixels.width(),x2);
    int top=Math.max(0,y1),bottom=Math.min(pixels.height(),y2);
    if(left>=right||top>=bottom)return WordStyles.Style.normal();
    boolean under=underline(pixels,left,top,right,bottom);
    String color=ink(pixels,left,top,right,bottom);
    String highlight=background(pixels,left,top,right,bottom);
    return new WordStyles.Style(false,under,color,highlight);
  }
  private static boolean underline(Pixels bitmap,int left,int top,int right,int bottom){
    if(right-left<9)return false;
    for(int y=Math.max(top+2,bottom-2);y<Math.min(bitmap.height(),bottom+6);y++){
      int dark=0,total=0;
      for(int x=left+2;x<right-2;x++){
        int c=bitmap.argb(x,y);
        if(Math.min(red(c),Math.min(green(c),blue(c)))<100)dark++;
        total++;
      }
      if(total>0&&(double)dark/total>0.78)return true;
    }
    return false;
  }
  private static String ink(Pixels b,int left,int top,int right,int bottom){
    long rs=0,gs=0,bs=0;int count=0;
    for(int y=top;y<bottom;y+=2)for(int x=left;x<right;x+=2){
      int c=b.argb(x,y),r=red(c),g=green(c),bl=blue(c);
      int max=Math.max(r,Math.max(g,bl)),min=Math.min(r,Math.min(g,bl));
      if(max-min>=65&&min<155&&max<245){
        rs+=r;gs+=g;bs+=bl;count++;
      }
    }
    if(count<Math.max(4,(right-left)*(bottom-top)/220))return null;
    return String.format(Locale.ROOT,"%02X%02X%02X",(int)(rs/count),(int)(gs/count),(int)(bs/count));
  }
  private static String background(Pixels b,int left,int top,int right,int bottom){
    int yellow=0,green=0,cyan=0,pink=0,samples=0;
    for(int y=top;y<bottom;y+=3)for(int x=left;x<right;x+=3){
      int c=b.argb(x,y),red=red(c),g=green(c),blue=blue(c);
      if(red>165&&g>165&&blue<Math.min(red,g)-40)yellow++;
      else if(g>155&&red<g-35&&blue<g-25)green++;
      else if(g>150&&blue>150&&red<Math.min(g,blue)-35)cyan++;
      else if(red>155&&blue>125&&g<Math.min(red,blue)-45)pink++;
      samples++;
    }
    int threshold=Math.max(5,(int)(samples*0.15));
    if(yellow>threshold)return "FFFF00";
    if(green>threshold)return "00FF00";
    if(cyan>threshold)return "00FFFF";
    if(pink>threshold)return "FF00FF";
    return null;
  }
}
