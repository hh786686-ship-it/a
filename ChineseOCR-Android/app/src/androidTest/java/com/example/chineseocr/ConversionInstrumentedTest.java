package com.example.chineseocr;

import android.app.Instrumentation;
import android.content.Intent;
import android.graphics.Canvas;
import android.graphics.Bitmap;
import android.graphics.Rect;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.pdf.PdfDocument;
import android.net.Uri;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;
import java.io.*;
import java.lang.reflect.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.zip.*;

@RunWith(AndroidJUnit4.class)
public final class ConversionInstrumentedTest {
  private MainActivity activity(){
    Instrumentation instr=InstrumentationRegistry.getInstrumentation();
    Intent intent=new Intent(instr.getTargetContext(),MainActivity.class);
    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
    return (MainActivity)instr.startActivitySync(intent);
  }

  static String unzip(File f,String name)throws Exception{
    try(ZipFile z=new ZipFile(f)){
      ZipEntry e=z.getEntry(name);assertNotNull(e);
      try(InputStream in=z.getInputStream(e);ByteArrayOutputStream o=new ByteArrayOutputStream()){
        byte[] b=new byte[8192];int n;while((n=in.read(b))!=-1)o.write(b,0,n);
        return o.toString("UTF-8");
      }
    }
  }

  static File writeZip(File out,String xml)throws Exception{
    try(ZipOutputStream z=new ZipOutputStream(new FileOutputStream(out))){
      z.putNextEntry(new ZipEntry("word/document.xml"));
      z.write(xml.getBytes(StandardCharsets.UTF_8));z.closeEntry();
    }return out;
  }

  @Test public void wordStylesSurviveDirectDocxAndPinyin()throws Exception{
    MainActivity a=activity();
    File file=new File(InstrumentationRegistry.getInstrumentation().getTargetContext().getCacheDir(),"style-input.docx");
    String input="<?xml version=\"1.0\"?><w:document xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\"><w:body><w:p><w:r><w:rPr><w:b/><w:u w:val=\"single\"/><w:color w:val=\"FF0000\"/><w:highlight w:val=\"yellow\"/></w:rPr><w:t>你好</w:t></w:r></w:p></w:body></w:document>";
    writeZip(file,input);
    Method extractor=MainActivity.class.getDeclaredMethod("extractDocx",Uri.class);extractor.setAccessible(true);
    @SuppressWarnings("unchecked") ArrayList<String> text=(ArrayList<String>)extractor.invoke(a,Uri.fromFile(file));
    assertEquals(1,text.size());assertTrue(text.get(0).contains("你好"));
    Field marks=MainActivity.class.getDeclaredField("docxMarks");marks.setAccessible(true);
    @SuppressWarnings("unchecked") ArrayList<ArrayList<AndroidStyle.Mark>> styles=(ArrayList<ArrayList<AndroidStyle.Mark>>)marks.get(a);
    assertEquals(text.get(0).length(),styles.get(0).size());
    assertTrue(styles.get(0).get(0).bold);
    assertTrue(styles.get(0).get(0).underline);
    assertEquals(Color.RED,styles.get(0).get(0).color);
    assertEquals(Color.YELLOW,styles.get(0).get(0).highlight);
    File output=new File(file.getParentFile(),"style-output.docx");
    Method exporter=MainActivity.class.getDeclaredMethod("makeDocx",File.class,ArrayList.class,ArrayList.class,ArrayList.class);
    exporter.setAccessible(true);
    exporter.invoke(a,output,new ArrayList<>(Arrays.asList(1)),text,styles);
    String xml=unzip(output,"word/document.xml");
    assertTrue(xml.contains("<w:b/>"));assertTrue(xml.contains("<w:u w:val=\"single\"/>"));
    assertTrue(xml.contains("FF0000"));assertTrue(xml.contains("FFFF00"));
    assertTrue(xml.contains("<w:ruby>"));
    assertTrue("third-tone ni must use caron",xml.contains("nǐ"));
    assertTrue("third-tone hao must use caron",xml.contains("hǎo"));
    byte[] actualDocx=java.nio.file.Files.readAllBytes(output.toPath());
    assertTrue(actualDocx.length>250);
    assertEquals((byte)'P',actualDocx[0]);
    assertEquals((byte)'K',actualDocx[1]);
    // Log the complete small test DOCX while the emulator app is still installed.
    android.util.Log.i("VERIFIED_DOCX_BYTES",
      android.util.Base64.encodeToString(actualDocx,android.util.Base64.NO_WRAP));
    // Verify the document is structurally valid WordprocessingML, not just well-formed XML.
    javax.xml.parsers.DocumentBuilderFactory factory=javax.xml.parsers.DocumentBuilderFactory.newInstance();
    factory.setNamespaceAware(true);
    org.w3c.dom.Document parsed=factory.newDocumentBuilder().parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
    org.w3c.dom.NodeList ruby=parsed.getElementsByTagNameNS("http://schemas.openxmlformats.org/wordprocessingml/2006/main","ruby");
    assertEquals(2,ruby.getLength());
    for(int i=0;i<ruby.getLength();i++)
      assertEquals("r",ruby.item(i).getParentNode().getLocalName());
    // Store the real emulator-exported DOCX for desktop parsing and rendering checks.
    // Use MediaStore Downloads: app-private files vanish when Gradle uninstalls test APK.
    android.content.ContentValues values=new android.content.ContentValues();
    values.put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME,"mlkit-verified-style-output.docx");
    values.put(android.provider.MediaStore.MediaColumns.MIME_TYPE,"application/vnd.openxmlformats-officedocument.wordprocessingml.document");
    values.put(android.provider.MediaStore.MediaColumns.RELATIVE_PATH,"Download/");
    android.net.Uri destination=a.getContentResolver().insert(
      android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI,values);
    assertNotNull("Failed to create public DOCX in MediaStore",destination);
    try(InputStream src=new FileInputStream(output);OutputStream dst=a.getContentResolver().openOutputStream(destination)){
      assertNotNull(dst);
      byte[] chunk=new byte[8192];int len;
      while((len=src.read(chunk))!=-1)dst.write(chunk,0,len);
    }
  }

  @Test public void realBundledMLKitRecognizesGeneratedChinesePdf()throws Exception{
    MainActivity a=activity();
    File f=new File(InstrumentationRegistry.getInstrumentation().getTargetContext().getCacheDir(),"test-ocr.pdf");
    PdfDocument pdf=new PdfDocument();
    PdfDocument.Page page=pdf.startPage(new PdfDocument.PageInfo.Builder(600,800,1).create());
    Canvas canvas=page.getCanvas();
    Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);
    paint.setColor(Color.BLACK);paint.setTextSize(48);
    canvas.drawColor(Color.WHITE);canvas.drawText("你好中国",70,160,paint);
    pdf.finishPage(page);
    try(FileOutputStream out=new FileOutputStream(f)){pdf.writeTo(out);}pdf.close();
    Method read=MainActivity.class.getDeclaredMethod("ocrPdf",Uri.class,String.class,ArrayList.class);
    read.setAccessible(true);
    ArrayList<Integer> selected=new ArrayList<>();
    @SuppressWarnings("unchecked") ArrayList<String> texts=(ArrayList<String>)read.invoke(a,Uri.fromFile(f),"1",selected);
    assertEquals(1,selected.size());assertEquals(1,texts.size());
    assertTrue("Recognized text: "+texts.get(0),texts.get(0).contains("你好"));
    Field markField=MainActivity.class.getDeclaredField("pageMarks");markField.setAccessible(true);
    @SuppressWarnings("unchecked") ArrayList<ArrayList<AndroidStyle.Mark>> marks=(ArrayList<ArrayList<AndroidStyle.Mark>>)markField.get(a);
    assertEquals(texts.get(0).length(),marks.get(0).size());
    File docx=new File(a.getCacheDir(),"pdf-ocr-output.docx");
    Method export=MainActivity.class.getDeclaredMethod("makeDocx",File.class,ArrayList.class,ArrayList.class,ArrayList.class);
    export.setAccessible(true);
    export.invoke(a,docx,selected,texts,marks);
    String xml=unzip(docx,"word/document.xml");
    assertTrue(xml.contains("<w:ruby>"));
    javax.xml.parsers.DocumentBuilderFactory factory=javax.xml.parsers.DocumentBuilderFactory.newInstance();
    factory.setNamespaceAware(true);
    org.w3c.dom.Document parsed=factory.newDocumentBuilder().parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
    org.w3c.dom.NodeList ruby=parsed.getElementsByTagNameNS("http://schemas.openxmlformats.org/wordprocessingml/2006/main","ruby");
    assertTrue("OCR output should contain Chinese pinyin",ruby.getLength()>=2);
    for(int i=0;i<ruby.getLength();i++)assertEquals("r",ruby.item(i).getParentNode().getLocalName());
    android.content.ContentValues values=new android.content.ContentValues();
    values.put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME,"mlkit-verified-pdf-ocr-output.docx");
    values.put(android.provider.MediaStore.MediaColumns.MIME_TYPE,"application/vnd.openxmlformats-officedocument.wordprocessingml.document");
    values.put(android.provider.MediaStore.MediaColumns.RELATIVE_PATH,"Download/");
    android.net.Uri destination=a.getContentResolver().insert(
      android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI,values);
    assertNotNull("Cannot save OCR DOCX for external verification",destination);
    try(InputStream src=new FileInputStream(docx);OutputStream dst=a.getContentResolver().openOutputStream(destination)){
      assertNotNull(dst);
      byte[] b=new byte[8192];int n;
      while((n=src.read(b))!=-1)dst.write(b,0,n);
    }
  }

  @Test public void contextualMandarinPinyinSurvivesWordExport()throws Exception{
    String chinese="银行行长喜欢音乐，也觉得快乐。重庆很重要。";
    Map<Integer,String> p=PinyinContext.overrides(chinese);
    assertEquals("yín",p.get(chinese.indexOf("银行")));
    assertEquals("háng",p.get(chinese.indexOf("银行")+1));
    assertEquals("zhǎng",p.get(chinese.indexOf("行长")+1));
    assertEquals("yuè",p.get(chinese.indexOf("音乐")+1));
    assertEquals("chóng",p.get(chinese.indexOf("重庆")));
    assertEquals("zhòng",p.get(chinese.indexOf("重要")));
    MainActivity a=activity();
    File out=new File(a.getCacheDir(),"context-output.docx");
    Method export=MainActivity.class.getDeclaredMethod("makeDocx",File.class,ArrayList.class,ArrayList.class,ArrayList.class);
    export.setAccessible(true);
    ArrayList<ArrayList<AndroidStyle.Mark>> marks=new ArrayList<>();
    ArrayList<AndroidStyle.Mark> m=new ArrayList<>();
    for(int i=0;i<chinese.length();i++)m.add(AndroidStyle.plain());
    marks.add(m);
    export.invoke(a,out,new ArrayList<>(Arrays.asList(1)),new ArrayList<>(Arrays.asList(chinese)),marks);
    String xml=unzip(out,"word/document.xml");
    for(String py:new String[]{"háng","zhǎng","yuè","chóng","zhòng"})
      assertTrue("Missing contextual pinyin "+py,xml.contains(py));
  }

  @Test public void pixelRecoveryFindsUnrecognizedSmallHeader()throws Exception{
    Bitmap bmp=Bitmap.createBitmap(500,300,Bitmap.Config.ARGB_8888);
    Canvas canvas=new Canvas(bmp);canvas.drawColor(Color.WHITE);
    Paint p=new Paint(Paint.ANTI_ALIAS_FLAG);p.setColor(Color.BLACK);p.setTextSize(20);
    canvas.drawText("小字标题",35,37,p);
    p.setTextSize(42);canvas.drawText("你好世界",35,175,p);
    ArrayList<Rect> recognized=new ArrayList<>();
    recognized.add(new Rect(30,127,240,190));
    ArrayList<Rect> recovery=SmallTextRegions.uncovered(bmp,recognized,2);
    assertTrue("Missed header was not considered for rescan: "+recovery,!recovery.isEmpty());
    assertTrue("Expected uncovered header near page top: "+recovery,recovery.get(0).top<50);
    assertTrue(recovery.size()<=2);
    bmp.recycle();
  }

  @Test public void multipleOriginalPagesProduceExplicitWordPageBreaks()throws Exception{
    MainActivity a=activity();
    File output=new File(a.getCacheDir(),"multipage-output.docx");
    Method export=MainActivity.class.getDeclaredMethod("makeDocx",File.class,ArrayList.class,ArrayList.class,ArrayList.class);
    export.setAccessible(true);
    ArrayList<ArrayList<AndroidStyle.Mark>> styles=new ArrayList<>();
    for(String text:new String[]{"第一页。","第二页。"}){
      ArrayList<AndroidStyle.Mark> marks=new ArrayList<>();
      for(int i=0;i<text.length();i++)marks.add(AndroidStyle.plain());
      styles.add(marks);
    }
    export.invoke(a,output,new ArrayList<>(Arrays.asList(7,9)),
      new ArrayList<>(Arrays.asList("第一页。","第二页。")),styles);
    String xml=unzip(output,"word/document.xml");
    assertEquals(1,xml.split("w:pageBreakBefore", -1).length-1);
    assertTrue(xml.contains("w:pgSz"));
    assertTrue(xml.contains("w:pgMar"));
    assertTrue(xml.contains(">7<"));
    assertTrue(xml.contains(">9<"));
    android.content.ContentValues values=new android.content.ContentValues();
    values.put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME,"mlkit-verified-two-source-pages.docx");
    values.put(android.provider.MediaStore.MediaColumns.MIME_TYPE,"application/vnd.openxmlformats-officedocument.wordprocessingml.document");
    values.put(android.provider.MediaStore.MediaColumns.RELATIVE_PATH,"Download/");
    Uri destination=a.getContentResolver().insert(
      android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI,values);
    assertNotNull(destination);
    try(InputStream src=new FileInputStream(output);OutputStream dst=a.getContentResolver().openOutputStream(destination)){
      assertNotNull(dst);
      byte[] b=new byte[8192];int n;
      while((n=src.read(b))!=-1)dst.write(b,0,n);
    }
  }

  @Test public void fontFittingHasOnlyElevenNineSeven()throws Exception{
    Method fit=MainActivity.class.getDeclaredMethod("fit",String.class);fit.setAccessible(true);
    int[] result=(int[])fit.invoke(null,"你好");
    assertArrayEquals(new int[]{11,6},result);
    int[] dense=(int[])fit.invoke(null,String.join("",Collections.nCopies(2000,"你好")));
    assertEquals(7,dense[0]);assertEquals(4,dense[1]);
  }
  @Test public void colouredTextOnYellowHighlightWithUnderline()throws Exception{
    Bitmap bmp=Bitmap.createBitmap(310,130,Bitmap.Config.ARGB_8888);
    Canvas c=new Canvas(bmp);c.drawColor(Color.WHITE);
    Paint p=new Paint(Paint.ANTI_ALIAS_FLAG);
    p.setColor(Color.YELLOW);c.drawRect(20,30,260,91,p);
    p.setColor(Color.rgb(220,25,25));p.setTextSize(40);
    c.drawText("你好世界",27,78,p);
    p.setColor(Color.BLACK);p.setStrokeWidth(2);
    c.drawLine(25,92,250,92,p);
    AndroidStyle.Mark mark=AndroidStyle.inspect(bmp,new Rect(20,30,260,94));
    assertTrue("Expected red text; detected "+Integer.toHexString(mark.color),
      Color.red(mark.color)>Color.green(mark.color)+35);
    assertTrue("Expected yellow highlight; detected "+Integer.toHexString(mark.highlight),
      mark.highlight!=Color.TRANSPARENT&&Color.red(mark.highlight)>180&&Color.green(mark.highlight)>170);
    assertTrue("Expected underline",mark.underline);
    bmp.recycle();
  }


  private static String hanOnly(String text) {
    StringBuilder b=new StringBuilder();
    for(int i=0;i<text.length();){
      int cp=text.codePointAt(i);i+=Character.charCount(cp);
      if(Character.UnicodeScript.of(cp)==Character.UnicodeScript.HAN)b.appendCodePoint(cp);
    }
    return b.toString();
  }
  private static int distance(String a,String b) {
    int[] prev=new int[b.length()+1],cur=new int[b.length()+1];
    for(int j=0;j<=b.length();j++)prev[j]=j;
    for(int i=1;i<=a.length();i++) {
      cur[0]=i;
      for(int j=1;j<=b.length();j++)
        cur[j]=Math.min(Math.min(cur[j-1]+1,prev[j]+1),
          prev[j-1]+(a.charAt(i-1)==b.charAt(j-1)?0:1));
      int[] swap=prev;prev=cur;cur=swap;
    }
    return prev[b.length()];
  }
  private static File makeBenchmarkPdf(File dest,String[] lines)throws Exception {
    PdfDocument pdf=new PdfDocument();
    PdfDocument.Page p=pdf.startPage(new PdfDocument.PageInfo.Builder(680,900,1).create());
    Canvas c=p.getCanvas();c.drawColor(Color.WHITE);
    Paint ink=new Paint(Paint.ANTI_ALIAS_FLAG|Paint.SUBPIXEL_TEXT_FLAG);
    ink.setColor(Color.BLACK);ink.setTextSize(44);
    for(int i=0;i<lines.length;i++)c.drawText(lines[i],38,100+i*90,ink);
    pdf.finishPage(p);
    try(FileOutputStream o=new FileOutputStream(dest)){pdf.writeTo(o);}
    pdf.close();return dest;
  }

  @Test public void twoDifferentChinesePdfsReach97PercentAndExportCorrectly()throws Exception {
    MainActivity a=activity();
    String[][] fixtures={
      {"今天天气很好我们出门散步","大家一起学习中文知识","老师正在教学生读书","我们每天都要认真学习",
       "这里有很多有趣故事","希望大家开心快乐成长","朋友之间互相帮助关心","今天我们练习写中国字"},
      {"中国有很多美丽的城市","北京上海广州都是城市","图书馆里有许多好书","孩子们喜欢阅读故事",
       "早上我们一起吃早餐","晚上大家回家休息睡觉","爸爸妈妈每天努力工作","我们喜欢春天的花朵"}
    };
    Method read=MainActivity.class.getDeclaredMethod("ocrPdf",Uri.class,String.class,ArrayList.class);
    read.setAccessible(true);
    Method export=MainActivity.class.getDeclaredMethod("makeDocx",File.class,ArrayList.class,ArrayList.class,ArrayList.class);
    export.setAccessible(true);
    Field markField=MainActivity.class.getDeclaredField("pageMarks");
    markField.setAccessible(true);
    for(int sample=0;sample<fixtures.length;sample++){
      String expected=hanOnly(String.join("",fixtures[sample]));
      File pdf=makeBenchmarkPdf(new File(a.getCacheDir(),"benchmark-"+(sample+1)+".pdf"),fixtures[sample]);
      ArrayList<Integer> selected=new ArrayList<>();
      @SuppressWarnings("unchecked") ArrayList<String> texts=(ArrayList<String>)read.invoke(a,Uri.fromFile(pdf),"1",selected);
      assertEquals(1,texts.size());assertEquals(1,selected.size());
      String actual=hanOnly(texts.get(0));
      int errors=distance(expected,actual);
      double accuracy=100.0*(1.0-(double)errors/expected.length());
      android.util.Log.i("OCR_BENCHMARK","PDF "+(sample+1)+" chars="+expected.length()+
        " errors="+errors+" accuracy="+String.format(Locale.US,"%.2f",accuracy)+"%"+
        " expected="+expected+" actual="+actual);
      assertTrue("PDF "+(sample+1)+" below 97% accuracy: "+accuracy+
        " expected="+expected+" actual="+actual,accuracy>=97.0);
      @SuppressWarnings("unchecked")
      ArrayList<ArrayList<AndroidStyle.Mark>> marks=
        (ArrayList<ArrayList<AndroidStyle.Mark>>)markField.get(a);
      File docx=new File(a.getCacheDir(),"benchmark-"+(sample+1)+".docx");
      export.invoke(a,docx,selected,texts,marks);
      assertTrue(docx.exists()&&docx.length()>400);
      String xml=unzip(docx,"word/document.xml");
      javax.xml.parsers.DocumentBuilderFactory factory=javax.xml.parsers.DocumentBuilderFactory.newInstance();
      factory.setNamespaceAware(true);
      org.w3c.dom.Document parsed=factory.newDocumentBuilder().parse(
        new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
      String ns="http://schemas.openxmlformats.org/wordprocessingml/2006/main";
      org.w3c.dom.NodeList bases=parsed.getElementsByTagNameNS(ns,"rubyBase");
      StringBuilder recovered=new StringBuilder();
      for(int i=0;i<bases.getLength();i++){
        org.w3c.dom.NodeList children=bases.item(i).getChildNodes();
        for(int j=0;j<children.getLength();j++)
          recovered.append(children.item(j).getTextContent());
      }
      assertEquals("Word output lost OCR Chinese characters",actual,hanOnly(recovered.toString()));
      android.content.ContentValues cv=new android.content.ContentValues();
      cv.put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME,"benchmark-"+(sample+1)+"-output.docx");
      cv.put(android.provider.MediaStore.MediaColumns.MIME_TYPE,
        "application/vnd.openxmlformats-officedocument.wordprocessingml.document");
      cv.put(android.provider.MediaStore.MediaColumns.RELATIVE_PATH,"Download/");
      Uri dst=a.getContentResolver().insert(android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI,cv);
      assertNotNull(dst);
      try(InputStream src=new FileInputStream(docx);OutputStream out=a.getContentResolver().openOutputStream(dst)){
        assertNotNull(out);byte[] bytes=new byte[8192];int n;
        while((n=src.read(bytes))!=-1)out.write(bytes,0,n);
      }
    }
  }
  @Test public void exportedDocxHandlesUnicodeAndEscaping()throws Exception{
    MainActivity a=activity();
    File output=new File(a.getCacheDir(),"unicode-output.docx");
    String input="中文 & < > \" 𠀀 😀 "+(char)0x0001+" 尾";
    Method export=MainActivity.class.getDeclaredMethod("makeDocx",File.class,ArrayList.class,ArrayList.class,ArrayList.class);
    export.setAccessible(true);
    ArrayList<ArrayList<AndroidStyle.Mark>> styles=new ArrayList<>();
    ArrayList<AndroidStyle.Mark> m=new ArrayList<>();
    for(int i=0;i<input.length();i++)m.add(AndroidStyle.plain());
    styles.add(m);
    export.invoke(a,output,new ArrayList<>(Arrays.asList(1)),new ArrayList<>(Arrays.asList(input)),styles);
    String xml=unzip(output,"word/document.xml");
    assertTrue(xml.contains("&amp;"));assertTrue(xml.contains("&lt;"));
    assertTrue(xml.contains("𠀀"));assertTrue(xml.contains("😀"));
    assertFalse(xml.contains(String.valueOf((char)0x0001)));
    javax.xml.parsers.DocumentBuilderFactory f=javax.xml.parsers.DocumentBuilderFactory.newInstance();
    f.setNamespaceAware(true);
    org.w3c.dom.Document d=f.newDocumentBuilder().parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
    org.w3c.dom.NodeList ruby=d.getElementsByTagNameNS("http://schemas.openxmlformats.org/wordprocessingml/2006/main","ruby");
    for(int i=0;i<ruby.getLength();i++)assertEquals("r",ruby.item(i).getParentNode().getLocalName());
  }

}
