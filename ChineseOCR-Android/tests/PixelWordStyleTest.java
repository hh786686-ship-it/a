package com.example.chineseocr;

import java.util.Arrays;

public final class PixelWordStyleTest {
  static final class Canvas implements PixelWordStyle.Pixels {
    final int w=200,h=80;final int[][] pixels=new int[h][w];
    Canvas(){for(int[] row:pixels)Arrays.fill(row,0xFFFFFFFF);}
    void box(int x1,int y1,int x2,int y2,int color){
      for(int y=y1;y<y2;y++)for(int x=x1;x<x2;x++)pixels[y][x]=color;
    }
    public int width(){return w;}
    public int height(){return h;}
    public int argb(int x,int y){return pixels[y][x];}
  }
  static void ok(boolean cond,String desc){
    if(!cond)throw new AssertionError(desc);
    System.out.println("PASS "+desc);
  }
  public static void main(String[] unused){
    Canvas red=new Canvas();
    for(int x=20;x<90;x+=4)red.box(x,18,x+2,36,0xFFCC0A28);
    red.box(20,40,90,41,0xFF000000);
    WordStyles.Style s=PixelWordStyle.estimate(red,20,15,90,39);
    ok(s.underline,"continuous underline detected beneath text");
    ok("CC0A28".equals(s.color),"red ink colour extracted from pixels");

    Canvas yellow=new Canvas();
    yellow.box(20,15,90,39,0xFFF8F000);
    for(int x=30;x<82;x+=5)yellow.box(x,19,x+2,35,0xFF000000);
    s=PixelWordStyle.estimate(yellow,20,15,90,39);
    ok("FFFF00".equals(s.highlight),"yellow highlighted text detected");

    Canvas cyan=new Canvas();
    cyan.box(20,15,90,39,0xFF00EEDD);
    for(int x=25;x<78;x+=5)cyan.box(x,19,x+2,36,0xFF000000);
    s=PixelWordStyle.estimate(cyan,20,15,90,39);
    ok("00FFFF".equals(s.highlight),"cyan highlighted text detected");

    Canvas clean=new Canvas();
    for(int x=25;x<80;x+=6)clean.box(x,18,x+2,33,0xFF000000);
    s=PixelWordStyle.estimate(clean,20,15,90,39);
    ok(!s.underline,"black non-underlined text not falsely underlined");
    ok("------".equals(s.color),"plain black text has no forced colour");
    ok("------".equals(s.highlight),"white background not falsely highlighted");
  }
}
