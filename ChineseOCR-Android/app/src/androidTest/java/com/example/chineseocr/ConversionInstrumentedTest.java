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
    // Verify the document is structurally valid WordprocessingML, not just well-formed XML.
    javax.xml.parsers.DocumentBuilderFactory factory=javax.xml.parsers.DocumentBuilderFactory.newInstance();
    factory.setNamespaceAware(true);
    org.w3c.dom.Document parsed=factory.newDocumentBuilder().parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
    org.w3c.dom.NodeList ruby=parsed.getElementsByTagNameNS("http://schemas.openxmlformats.org/wordprocessingml/2006/main","ruby");
    assertEquals(2,ruby.getLength());
    for(int i=0;i<ruby.getLength();i++)
      assertEquals("r",ruby.item(i).getParentNode().getLocalName());
    // Store the real emulator-exported DOCX for desktop parsing and rendering checks.
    File proof=new File(a.getExternalFilesDir(null),"verified-style-output.docx");
    try(InputStream src=new FileInputStream(output);OutputStream dst=new FileOutputStream(proof)){
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
