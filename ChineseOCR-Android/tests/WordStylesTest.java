package com.example.chineseocr;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.zip.*;
import javax.xml.parsers.*;
import org.w3c.dom.*;

public final class WordStylesTest {
  static void ok(boolean condition,String description){
    if(!condition)throw new AssertionError(description);
    System.out.println("PASS "+description);
  }
  public static void main(String[] args)throws Exception{
    String ns="http://schemas.openxmlformats.org/wordprocessingml/2006/main";
    String sample="<w:document xmlns:w=\""+ns+"\"><w:body><w:p>"
      +"<w:r><w:rPr><w:color w:val=\"FF0000\"/></w:rPr><w:t>红</w:t></w:r>"
      +"<w:r><w:rPr><w:u w:val=\"single\"/><w:color w:val=\"0000FF\"/></w:rPr><w:t>蓝</w:t></w:r>"
      +"<w:r><w:rPr><w:highlight w:val=\"green\"/></w:rPr><w:t>绿</w:t></w:r>"
      +"<w:r><w:rPr><w:shd w:fill=\"FFA500\"/></w:rPr><w:t>橙</w:t></w:r>"
      +"<w:r><w:rPr><w:b/></w:rPr><w:t>粗</w:t></w:r>"
      +"<w:r><w:rPr><w:u w:val=\"none\"/><w:color w:val=\"auto\"/></w:rPr><w:t>无</w:t></w:r>"
      +"</w:p></w:body></w:document>";
    DocumentBuilderFactory f=DocumentBuilderFactory.newInstance();f.setNamespaceAware(true);
    Document d=f.newDocumentBuilder().parse(new java.io.ByteArrayInputStream(sample.getBytes(StandardCharsets.UTF_8)));
    NodeList source=d.getElementsByTagNameNS(ns,"r");
    StringBuilder encoded=new StringBuilder();
    for(int i=0;i<source.getLength();i++){
      Node r=source.item(i);
      WordStyles.Style st=WordStyles.fromRun(r);
      NodeList t=((Element)r).getElementsByTagNameNS(ns,"t");
      encoded.append(WordStyles.begin(st)).append(t.item(0).getTextContent()).append(WordStyles.end());
    }
    StringBuilder output=new StringBuilder();
    WordStyles.appendText(output,encoded.toString(),c->"pīn");
    String xml="<w:document xmlns:w=\""+ns+"\"><w:body>"+output+"</w:body></w:document>";
    Document export=f.newDocumentBuilder().parse(new java.io.ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
    NodeList ruby=export.getElementsByTagNameNS(ns,"ruby");
    ok(ruby.getLength()==6,"six Chinese characters retained with ruby");
    ok(xml.contains("<w:color w:val=\"FF0000\"/>"),"original red text remains red");
    ok(xml.contains("<w:u w:val=\"single\"/><w:color w:val=\"0000FF\"/>"),"blue underlined text retains both styles");
    ok(xml.contains("<w:highlight w:val=\"green\"/>"),"green highlight remains green");
    ok(xml.contains("w:fill=\"FFA500\""),"custom orange shaded background preserved");
    ok(xml.contains("<w:b/>"),"bold retained");
    ok(!xml.contains("w:val=\"auto\"/></w:rPr><w:t>无"),"no automatic colour incorrectly emitted");
    // Prevent the previous bug: style spilling from one coloured run to next.
    for(int i=0;i<ruby.getLength();i++){
      Element e=(Element)ruby.item(i);
      Element base=(Element)e.getElementsByTagNameNS(ns,"rubyBase").item(0);
      String letter=base.getTextContent();
      String color=null,under=null,fill=null;
      NodeList props=base.getElementsByTagNameNS(ns,"rPr");
      if(props.getLength()>0){
        Element pr=(Element)props.item(0);
        NodeList children=pr.getChildNodes();
        for(int q=0;q<children.getLength();q++){
          Node n=children.item(q);
          if("color".equals(n.getLocalName()))color=((Element)n).getAttributeNS(ns,"val");
          if("u".equals(n.getLocalName()))under=((Element)n).getAttributeNS(ns,"val");
          if("shd".equals(n.getLocalName()))fill=((Element)n).getAttributeNS(ns,"fill");
        }
      }
      if(letter.equals("红"))ok("FF0000".equals(color)&&under==null,"red ruby-base styled, not underlined");
      if(letter.equals("蓝"))ok("0000FF".equals(color)&&"single".equals(under),"blue ruby-base underlined");
      if(letter.equals("橙"))ok("FFA500".equals(fill),"orange ruby-base shading");
      if(letter.equals("无"))ok(color==null&&under==null&&fill==null,"unstyled word not contaminated");
    }
    StringBuilder breakOut=new StringBuilder();
    WordStyles.appendText(breakOut,WordStyles.begin(new WordStyles.Style(false,true,"AA2244",null))+"中\n文"+WordStyles.end(),c->"zhōng");
    ok(breakOut.toString().split("w:val=\"AA2244\"", -1).length==3,"colour persists across newlines");
    // Verify generated Word XML can be put into a normal, valid DOCX ZIP.
    if(args.length>0){
      String content="<w:document xmlns:w=\""+ns+"\"><w:body>"+output+"<w:sectPr/></w:body></w:document>";
      String ct="<?xml version=\"1.0\"?><Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\"><Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/><Default Extension=\"xml\" ContentType=\"application/xml\"/><Override PartName=\"/word/document.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml\"/></Types>";
      String rels="<?xml version=\"1.0\"?><Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\"><Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument\" Target=\"word/document.xml\"/></Relationships>";
      try(ZipOutputStream z=new ZipOutputStream(new FileOutputStream(args[0]))){
        entry(z,"[Content_Types].xml",ct);
        entry(z,"_rels/.rels",rels);
        entry(z,"word/document.xml",content);
      }
      System.out.println("EXPORTED "+args[0]);
    }
  }
  static void entry(ZipOutputStream out,String name,String text)throws Exception{
    out.putNextEntry(new ZipEntry(name));out.write(text.getBytes(StandardCharsets.UTF_8));out.closeEntry();
  }
}
