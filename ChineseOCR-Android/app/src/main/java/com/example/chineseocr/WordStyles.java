package com.example.chineseocr;

import java.util.Locale;
import org.w3c.dom.*;

/**
 * Loss-minimising Word run formatting for directly editable DOCX.
 * Stores bold/underline + RGB font/highlight within an explicit marker,
 * then emits the same attributes onto ordinary and ruby-base runs.
 */
public final class WordStyles {
  private WordStyles(){}
  static final char MARK='\uE000';
  private static final String NONE="------";
  private static final String NS="http://schemas.openxmlformats.org/wordprocessingml/2006/main";

  public interface Pronunciation { String forCharacter(char character); }

  public static final class Style {
    public final boolean bold,underline;
    public final String color, highlight;
    public Style(boolean bold,boolean underline,String color,String highlight){
      this.bold=bold;this.underline=underline;
      this.color=hexOrNone(color);this.highlight=hexOrNone(highlight);
    }
    public static Style normal(){return new Style(false,false,NONE,NONE);}
  }

  static String hexOrNone(String value){
    if(value==null)return NONE;
    String x=value.trim().replace("#","").toUpperCase(Locale.ROOT);
    if(x.matches("[0-9A-F]{6}"))return x;
    return NONE;
  }

  private static String attribute(Node n,String name){
    if(n==null||n.getAttributes()==null)return null;
    Node a=n.getAttributes().getNamedItemNS(NS,name);
    return a==null?null:a.getNodeValue();
  }
  private static boolean enabled(Node n){
    String v=attribute(n,"val");
    return v==null||!v.equalsIgnoreCase("false")&&!v.equalsIgnoreCase("off")&&!v.equals("0");
  }
  private static String namedColor(String value){
    if(value==null)return NONE;
    switch(value.toLowerCase(Locale.ROOT)){
      case "yellow":return "FFFF00";
      case "green":return "00FF00";
      case "cyan":return "00FFFF";
      case "magenta":return "FF00FF";
      case "blue":return "0000FF";
      case "red":return "FF0000";
      case "darkblue":return "000080";
      case "darkcyan":return "008080";
      case "darkgreen":return "008000";
      case "darkmagenta":return "800080";
      case "darkred":return "800000";
      case "darkyellow":return "808000";
      case "darkgray":return "808080";
      case "lightgray":return "C0C0C0";
      case "black":return "000000";
      case "white":return "FFFFFF";
      default:return NONE;
    }
  }
  private static String highlightName(String hex){
    switch(hex){
      case "FFFF00":return "yellow";
      case "00FF00":return "green";
      case "00FFFF":return "cyan";
      case "FF00FF":return "magenta";
      case "0000FF":return "blue";
      case "FF0000":return "red";
      case "000080":return "darkBlue";
      case "008080":return "darkCyan";
      case "008000":return "darkGreen";
      case "800080":return "darkMagenta";
      case "800000":return "darkRed";
      case "808000":return "darkYellow";
      case "808080":return "darkGray";
      case "C0C0C0":return "lightGray";
      case "000000":return "black";
      default:return null;
    }
  }

  public static Style fromRun(Node run){
    boolean bold=false,under=false;
    String color=NONE, highlight=NONE;
    if(run==null)return Style.normal();
    NodeList children=run.getChildNodes();
    for(int i=0;i<children.getLength();i++){
      Node pr=children.item(i);
      if(!NS.equals(pr.getNamespaceURI())||!"rPr".equals(pr.getLocalName()))continue;
      NodeList properties=pr.getChildNodes();
      for(int j=0;j<properties.getLength();j++){
        Node p=properties.item(j);String n=p.getLocalName();
        if("b".equals(n)||"bCs".equals(n))bold|=enabled(p);
        if("u".equals(n)) {
          String v=attribute(p,"val");
          under=v==null||!"none".equalsIgnoreCase(v);
        }
        if("color".equals(n))color=hexOrNone(attribute(p,"val"));
        if("highlight".equals(n))highlight=namedColor(attribute(p,"val"));
        if("shd".equals(n)){
          String fill=hexOrNone(attribute(p,"fill"));
          if(!NONE.equals(fill)&&!"FFFFFF".equals(fill))highlight=fill;
        }
      }
    }
    return new Style(bold,under,color,highlight);
  }

  public static String begin(Style style){
    int flags=(style.bold?1:0)|(style.underline?2:0);
    return new StringBuilder().append(MARK).append('S')
      .append((char)('0'+flags)).append(style.color)
      .append(style.highlight).toString();
  }
  public static String end(){return ""+MARK+'E';}
  public static String escape(String text){
    return text.replace("&","&amp;").replace("<","&lt;").replace(">","&gt;").replace("\"","&quot;").replace("'","&apos;");
  }
  public static String xmlProperties(Style style){
    StringBuilder s=new StringBuilder();
    if(style.bold)s.append("<w:b/>");
    if(style.underline)s.append("<w:u w:val=\"single\"/>");
    if(!NONE.equals(style.color))s.append("<w:color w:val=\"").append(style.color).append("\"/>");
    if(!NONE.equals(style.highlight)){
      String name=highlightName(style.highlight);
      if(name!=null)s.append("<w:highlight w:val=\"").append(name).append("\"/>");
      s.append("<w:shd w:val=\"clear\" w:color=\"auto\" w:fill=\"")
       .append(style.highlight).append("\"/>");
    }
    return s.toString();
  }
  private static String ordinary(String text,Style st){
    return "<w:r><w:rPr>"+xmlProperties(st)+"</w:rPr><w:t xml:space=\"preserve\">"+escape(text)+"</w:t></w:r>";
  }
  private static String ruby(char c,String py,Style st){
    return "<w:ruby><w:rubyPr><w:rubyAlign w:val=\"center\"/>"
      +"<w:hps w:val=\"11\"/><w:hpsRaise w:val=\"22\"/>"
      +"<w:hpsBaseText w:val=\"20\"/></w:rubyPr>"
      +"<w:rt><w:r><w:rPr><w:sz w:val=\"11\"/></w:rPr>"
      +"<w:t>"+escape(py)+"</w:t></w:r></w:rt>"
      +"<w:rubyBase><w:r><w:rPr>"+xmlProperties(st)+"</w:rPr>"
      +"<w:t>"+escape(String.valueOf(c))+"</w:t></w:r></w:rubyBase></w:ruby>";
  }
  private static void paragraphStart(StringBuilder out){
    out.append("<w:p><w:pPr><w:spacing w:before=\"30\" w:after=\"30\" w:line=\"600\" w:lineRule=\"atLeast\"/></w:pPr>");
  }
  public static void appendText(StringBuilder out,String encoded,Pronunciation pronunciation){
    Style st=Style.normal();
    paragraphStart(out);
    for(int i=0;i<encoded.length();i++){
      char c=encoded.charAt(i);
      if(c==MARK&&i+1<encoded.length()){
        char type=encoded.charAt(++i);
        if(type=='E'){st=Style.normal();continue;}
        if(type=='S'&&i+13<encoded.length()){
          int flags=encoded.charAt(i+1)-'0';
          String color=encoded.substring(i+2,i+8);
          String highlight=encoded.substring(i+8,i+14);
          st=new Style((flags&1)!=0,(flags&2)!=0,color,highlight);
          i+=13;continue;
        }
        throw new IllegalArgumentException("Corrupt run-style marker");
      }
      if(c=='\r')continue;
      if(c=='\n'){
        out.append("</w:p>");paragraphStart(out);continue;
      }
      String py=pronunciation.forCharacter(c);
      out.append(py==null||py.isEmpty()?ordinary(String.valueOf(c),st):ruby(c,py,st));
    }
    out.append("</w:p>");
  }
}
