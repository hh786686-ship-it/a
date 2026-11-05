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

    Canvas bold=new Canvas();
    for(int x=25;x<120;x+=7)bold.box(x,16,x+4,38,0xFF000000);
    s=PixelWordStyle.estimate(bold,20,14,125,40);
    ok(s.bold,"thick scanned strokes classified bold");
    ok(!s.underline,"bold glyph strokes do not become an underline");

    Canvas thin=new Canvas();
    for(int x=25;x<120;x+=7)thin.box(x,16,x+1,38,0xFF000000);
    s=PixelWordStyle.estimate(thin,20,14,125,40);
    ok(!s.bold,"thin scanned strokes remain normal weight");

    Canvas pastelWatermark=new Canvas();
    pastelWatermark.box(20,15,120,39,0xFFFFAAAA);
    for(int x=25;x<115;x+=6)pastelWatermark.box(x,17,x+2,36,0xFF000000);
    s=PixelWordStyle.estimate(pastelWatermark,20,15,120,39);
    ok("------".equals(s.color),"light red watermark is not font colour");
    ok("------".equals(s.highlight),"light red watermark is not highlighting");

    Canvas darkCover=new Canvas();
    darkCover.box(20,14,120,45,0xFF555555);
    for(int x=25;x<115;x+=9)darkCover.box(x,18,x+3,40,0xFFFFFFFF);
    s=PixelWordStyle.estimate(darkCover,20,14,120,45);
    ok("FFFFFF".equals(s.color),"white cover lettering retains white font colour");
    ok("505050".equals(s.highlight),"white cover lettering remains visible on dark shading");
    ok(!s.bold,"dark cover background is not misclassified as bold text");

    // End-to-end style metadata -> actual Word ruby-base XML export.
    WordStyles.Style thick=PixelWordStyle.estimate(bold,20,14,125,40);
    WordStyles.Style underlinedRed=PixelWordStyle.estimate(red,20,15,90,39);
    WordStyles.Style yellowHighlight=PixelWordStyle.estimate(yellow,20,15,90,39);
    String encoded=WordStyles.begin(thick)+"粗"+WordStyles.end()
      +WordStyles.begin(underlinedRed)+"红"+WordStyles.end()
      +WordStyles.begin(yellowHighlight)+"黄"+WordStyles.end();
    StringBuilder docx=new StringBuilder();
    WordStyles.appendText(docx,encoded,c->"zhōng");
    String xml=docx.toString();
    ok(xml.contains("<w:b/>"),"scanned bold survives conversion to Word ruby XML");
    ok(xml.contains("<w:u w:val=\"single\"/>"),"scanned underline survives conversion to Word ruby XML");
    ok(xml.contains("<w:color w:val=\"CC0A28\"/>"),"scanned red ink survives conversion to Word ruby XML");
    ok(xml.contains("w:fill=\"FFFF00\""),"scanned yellow highlight survives conversion to Word ruby XML");



  }
}
