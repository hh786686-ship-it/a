package com.example.chineseocr;

import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Rect;
import com.google.mlkit.vision.text.Text;
import java.util.*;

/** Pixel-based document style recovery. It is an Android adaptation, not byte-identical Python v47. */
final class AndroidStyle {
  static final class Mark {
    boolean bold, underline;
    int color=Color.BLACK,highlight=Color.TRANSPARENT;
    Mark copy(){Mark m=new Mark();m.bold=bold;m.underline=underline;m.color=color;m.highlight=highlight;return m;}
  }
  static Mark plain(){return new Mark();}
  static String rgb(int c){return String.format(Locale.US,"%02X%02X%02X",Color.red(c),Color.green(c),Color.blue(c));}
  static String rpr(Mark m,int chineseHalfPts){
    if(m==null)m=plain();
    StringBuilder s=new StringBuilder("<w:rPr><w:sz w:val=\"").append(chineseHalfPts).append("\"/>");
    if(m.bold)s.append("<w:b/>");
    if(m.underline)s.append("<w:u w:val=\"single\"/>");
    if(m.color!=Color.BLACK)s.append("<w:color w:val=\"").append(rgb(m.color)).append("\"/>");
    if(m.highlight!=Color.TRANSPARENT)s.append("<w:shd w:val=\"clear\" w:color=\"auto\" w:fill=\"").append(rgb(m.highlight)).append("\"/>");
    return s.append("</w:rPr>").toString();
  }
  static Mark parseRun(org.w3c.dom.Node node){
    Mark m=plain();
    org.w3c.dom.Node run=node;
    while(run!=null&&!"r".equals(run.getLocalName()))run=run.getParentNode();
    if(run==null)return m;
    org.w3c.dom.NodeList children=run.getChildNodes();
    for(int i=0;i<children.getLength();i++){
      org.w3c.dom.Node pr=children.item(i);
      if(!"rPr".equals(pr.getLocalName()))continue;
      org.w3c.dom.NodeList attrs=pr.getChildNodes();
      for(int j=0;j<attrs.getLength();j++){
        org.w3c.dom.Node a=attrs.item(j);
        String key=a.getLocalName();String value=attr(a,"val");
        if("b".equals(key))m.bold=!"0".equals(value)&&!"false".equals(value);
        if("u".equals(key))m.underline=!"none".equals(value);
        if("color".equals(key)&&value!=null&&value.matches("[0-9A-Fa-f]{6}"))m.color=Color.rgb(Integer.parseInt(value.substring(0,2),16),Integer.parseInt(value.substring(2,4),16),Integer.parseInt(value.substring(4,6),16));
        if("highlight".equals(key))m.highlight=highlightName(value);
        if("shd".equals(key)){String fill=attr(a,"fill");if(fill!=null&&fill.matches("[0-9A-Fa-f]{6}"))m.highlight=Color.rgb(Integer.parseInt(fill.substring(0,2),16),Integer.parseInt(fill.substring(2,4),16),Integer.parseInt(fill.substring(4,6),16));}
      }
    }
    return m;
  }
  static String attr(org.w3c.dom.Node n,String key){
    org.w3c.dom.NamedNodeMap attrs=n.getAttributes();if(attrs==null)return null;
    for(int i=0;i<attrs.getLength();i++)if(key.equals(attrs.item(i).getLocalName()))return attrs.item(i).getNodeValue();
    return null;
  }
  static int highlightName(String s){
    if(s==null)return Color.TRANSPARENT;
    switch(s){case "yellow":return Color.YELLOW;case "green":return Color.GREEN;case "cyan":return Color.CYAN;case "magenta":return Color.MAGENTA;case "red":return Color.RED;case "blue":return Color.BLUE;case "darkYellow":return Color.rgb(190,155,0);default:return Color.TRANSPARENT;}
  }
  static ArrayList<Mark> inspectLine(Bitmap bmp,Text.Line line){
    String text=line.getText();
    ArrayList<Mark> output=new ArrayList<>();
    for(int i=0;i<text.length();i++)output.add(plain());
    ArrayList<Float> thick=new ArrayList<>();
    ArrayList<Mark> detected=new ArrayList<>();
    ArrayList<Integer> offsets=new ArrayList<>();
    for(Text.Element el:line.getElements()){
      Rect r=el.getBoundingBox();String t=el.getText();if(r==null||t==null||t.isEmpty())continue;
      int at=text.indexOf(t,offsets.isEmpty()?0:offsets.get(offsets.size()-1));
      if(at<0)at=text.indexOf(t);if(at<0)continue;
      offsets.add(at+t.length());
      int count=t.length();
      for(int k=0;k<count;k++){
        int left=r.left+(r.width()*k)/count,right=r.left+(r.width()*(k+1))/count;
        Rect c=new Rect(left,r.top,right,r.bottom);
        Mark m=inspect(bmp,c);
        float density=inkDensity(bmp,c);
        detected.add(m);thick.add(density);
        if(at+k<output.size())output.set(at+k,m);
      }
    }
    // v47-inspired relative ink weighting, with local robust reference; avoid bold on isolated thick glyph.
    if(thick.size()>=5){
      ArrayList<Float> sorted=new ArrayList<>(thick);Collections.sort(sorted);
      float median=sorted.get(sorted.size()/2);
      float low=sorted.get(Math.max(0,sorted.size()/4));
      float threshold=Math.max(median*1.34f,low*1.7f);
      int k=0;
      for(Text.Element el:line.getElements()){
        String t=el.getText();if(t==null)continue;
        int at=text.indexOf(t);if(at<0)continue;
        for(int j=0;j<t.length()&&k<thick.size();j++,k++){
          boolean near=(k>0&&thick.get(k-1)>threshold*.87f)||(k+1<thick.size()&&thick.get(k+1)>threshold*.87f);
          if(at+j<output.size())output.get(at+j).bold=thick.get(k)>threshold&&near;
        }
      }
    }
    return output;
  }
  static float inkDensity(Bitmap bmp,Rect b){
    Rect r=inside(b,bmp);if(r.width()<2||r.height()<2)return 0;
    int dark=0,all=0;int step=Math.max(1,Math.min(r.width(),r.height())/25);
    for(int y=r.top;y<r.bottom;y+=step)for(int x=r.left;x<r.right;x+=step){
      int c=bmp.getPixel(x,y),max=Math.max(Color.red(c),Math.max(Color.green(c),Color.blue(c)));
      if(max<155)dark++;all++;
    }
    return all==0?0:((float)dark/all);
  }
  static Rect inside(Rect b,Bitmap bmp){
    return new Rect(Math.max(0,b.left),Math.max(0,b.top),Math.min(bmp.getWidth(),b.right),Math.min(bmp.getHeight(),b.bottom));
  }
  static Mark inspect(Bitmap bmp,Rect b){
    Mark m=plain();Rect r=inside(b,bmp);if(r.isEmpty())return m;
    int[] ink=new int[3],bg=new int[3];int dark=0,bright=0;
    int step=Math.max(1,Math.min(r.width(),r.height())/22);
    for(int y=r.top;y<r.bottom;y+=step)for(int x=r.left;x<r.right;x+=step){
      int c=bmp.getPixel(x,y),red=Color.red(c),green=Color.green(c),blue=Color.blue(c);
      int max=Math.max(red,Math.max(green,blue)),min=Math.min(red,Math.min(green,blue));
      if(max<160){ink[0]+=red;ink[1]+=green;ink[2]+=blue;dark++;}
      else if(max>170&&max-min>35&&min>85){bg[0]+=red;bg[1]+=green;bg[2]+=blue;bright++;}
    }
    if(dark>=3){
      int red=ink[0]/dark,green=ink[1]/dark,blue=ink[2]/dark;
      if(Math.max(red,Math.max(green,blue))-Math.min(red,Math.min(green,blue))>30)m.color=Color.rgb(red,green,blue);
    }
    if(bright>=Math.max(4,dark/3))m.highlight=Color.rgb(bg[0]/bright,bg[1]/bright,bg[2]/bright);
    // A horizontal dark baseline immediately below the glyph often signals underline.
    int from=Math.max(0,r.bottom-3),to=Math.min(bmp.getHeight()-1,r.bottom+Math.max(2,r.height()/6));
    int minX=Math.max(0,r.left+1),maxX=Math.min(bmp.getWidth()-1,r.right-1);
    for(int y=from;y<=to;y++){
      int hits=0,total=0;
      for(int x=minX;x<=maxX;x++){
        int c=bmp.getPixel(x,y);
        if(Math.max(Color.red(c),Math.max(Color.green(c),Color.blue(c)))<125)hits++;
        total++;
      }
      if(total>4&&hits>=total*.82f){m.underline=true;break;}
    }
    return m;
  }
}
