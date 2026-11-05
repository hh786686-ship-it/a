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
  private static boolean darkNeutral(int c){
    int r=red(c),g=green(c),b=blue(c);
    int max=Math.max(r,Math.max(g,b)),min=Math.min(r,Math.min(g,b));
    return max<145&&max-min<46;
  }
  public static WordStyles.Style estimate(Pixels pixels,int x1,int y1,int x2,int y2){
    if(pixels==null||x2<=x1||y2<=y1)return WordStyles.Style.normal();
    int left=Math.max(0,x1),right=Math.min(pixels.width(),x2);
    int top=Math.max(0,y1),bottom=Math.min(pixels.height(),y2);
    if(left>=right||top>=bottom)return WordStyles.Style.normal();
    boolean under=underline(pixels,left,top,right,bottom);
    boolean bold=bold(pixels,left,top,right,bottom);
    String color=ink(pixels,left,top,right,bottom);
    String highlight=background(pixels,left,top,right,bottom);
    return new WordStyles.Style(bold,under,color,highlight);
  }
  private static boolean bold(Pixels p,int l,int t,int r,int b){
    // In the source scan bold ink has many adjacent 2x2 black blocks.
    // This ratio is normalized against ink pixels, unlike a fixed darkness test.
    int height=b-t,width=r-l;
    if(height<11||width<14)return false;
    int end=Math.max(t+6,b-Math.max(2,height/9));
    long ink=0,square=0;
    for(int y=t;y<end;y++)for(int x=l;x<r;x++){
      if(!darkNeutral(p.argb(x,y)))continue;
      ink++;
      if(x+1<r&&y+1<end&&darkNeutral(p.argb(x+1,y))
          &&darkNeutral(p.argb(x,y+1))&&darkNeutral(p.argb(x+1,y+1)))square++;
    }
    return ink>=Math.max(24,width/7)&&square/(double)ink>0.45;
  }
  private static boolean underline(Pixels p,int l,int t,int r,int b){
    int width=r-l,height=b-t;
    if(width<28||height<10)return false;
    int first=Math.max(t+6,t+(int)(height*0.65));
    int last=Math.min(p.height()-1,b+6);
    for(int y=first;y<=last;y++){
      int dark=0,run=0,longest=0;
      for(int x=l+2;x<r-2;x++){
        if(darkNeutral(p.argb(x,y))){dark++;run++;longest=Math.max(longest,run);}
        else run=0;
      }
      double density=dark/(double)Math.max(1,width-4);
      if(density<0.20)continue;
      // Broken underlines may retain long dark horizontal segments.
      if(longest>=Math.max(23,(int)(height*1.7)))return true;
      if(density<0.28)continue;
      // A sharp horizontal rule close to the baseline, not simply thick glyphs.
      int startRef=Math.max(t,y-8),endRef=Math.max(startRef+1,y-3);
      double prior=0;
      for(int yy=startRef;yy<endRef;yy++){
        int n=0;for(int x=l+2;x<r-2;x++)if(darkNeutral(p.argb(x,yy)))n++;
        prior+=n/(double)Math.max(1,width-4);
      }
      prior/=(endRef-startRef);
      if(density<prior*1.23+0.015)continue;
      double after=0;int nRows=0;
      for(int yy=y+2;yy<=Math.min(y+5,p.height()-1);yy++){
        int n=0;for(int x=l+2;x<r-2;x++)if(darkNeutral(p.argb(x,yy)))n++;
        after+=n/(double)Math.max(1,width-4);nRows++;
      }
      if(nRows>0&&density<after/nRows*1.35+0.03)continue;
      return true;
    }
    return false;
  }
  private static String ink(Pixels p,int l,int t,int r,int b){
    long rs=0,gs=0,bs=0;int chromatic=0,black=0;
    for(int y=t;y<b;y+=2)for(int x=l;x<r;x+=2){
      int c=p.argb(x,y),red=red(c),green=green(c),blue=blue(c);
      int max=Math.max(red,Math.max(green,blue)),min=Math.min(red,Math.min(green,blue));
      if(darkNeutral(c)){black++;continue;}
      // Exclude pastel-red watermarks but allow strongly coloured dark text.
      if(max-min>=65&&min<85&&max<=235){
        rs+=red;gs+=green;bs+=blue;chromatic++;
      }
    }
    if(chromatic<Math.max(5,(r-l)*(b-t)/180))return null;
    // Predominantly black text crossing red watermark must remain black.
    if(chromatic/(double)Math.max(1,chromatic+black)<0.55)return null;
    return String.format(Locale.ROOT,"%02X%02X%02X",(int)(rs/chromatic),
      (int)(gs/chromatic),(int)(bs/chromatic));
  }
  private static String background(Pixels b,int left,int top,int right,int bottom){
    int yellow=0,green=0,cyan=0,samples=0;
    for(int y=top;y<bottom;y+=3)for(int x=left;x<right;x+=3){
      int c=b.argb(x,y),red=red(c),g=green(c),blue=blue(c);
      if(red>165&&g>165&&blue<Math.min(red,g)-40)yellow++;
      else if(g>155&&red<g-35&&blue<g-25)green++;
      else if(g>150&&blue>150&&red<Math.min(g,blue)-35)cyan++;
      samples++;
    }
    int threshold=Math.max(5,(int)(samples*0.22));
    if(yellow>threshold)return "FFFF00";
    if(green>threshold)return "00FF00";
    if(cyan>threshold)return "00FFFF";
    // Pastel-red scan watermarks must not become Word highlights.
    return null;
  }
}
