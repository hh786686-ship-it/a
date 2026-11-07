package com.example.chineseocr;

import android.app.Instrumentation;
import android.graphics.Canvas;
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
    final MainActivity[] holder=new MainActivity[1];
    instr.runOnMainSync(()->{
      holder[0]=new MainActivity();
      holder[0].attachBaseContext(instr.getTargetContext());
    });
    return holder[0];
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
}
