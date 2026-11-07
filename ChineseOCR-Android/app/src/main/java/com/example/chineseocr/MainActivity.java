package com.example.chineseocr;

import android.app.Activity;
import android.os.Bundle;
import android.content.*;
import android.graphics.*;

import java.nio.FloatBuffer;
import android.graphics.pdf.PdfRenderer;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.view.*;
import android.widget.*;
import net.sourceforge.pinyin4j.PinyinHelper;
import net.sourceforge.pinyin4j.format.*;
import org.w3c.dom.*;
import javax.xml.parsers.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.zip.*;

public class MainActivity extends Activity {
  private final ExecutorService worker=Executors.newSingleThreadExecutor();
  private Uri source; private File result;
  private final ArrayList<ArrayList<AndroidStyle.Mark>> pageMarks=new ArrayList<>();
  private final ArrayList<ArrayList<AndroidStyle.Mark>> docxMarks=new ArrayList<>();
  private EditText range; private TextView message; private Button start,save;
  private static final int OPEN=1,EXPORT=2;
  @Override public void onCreate(Bundle b) {
    super.onCreate(b);
    LinearLayout box=new LinearLayout(this);box.setOrientation(1);box.setPadding(26,30,26,24);
    ScrollView sc=new ScrollView(this);sc.addView(box);setContentView(sc);
    TextView title=new TextView(this);title.setText("Offline ML Kit OCR + Pinyin");title.setTextSize(25);box.addView(title);
    TextView info=new TextView(this);info.setText("Offline ML Kit Chinese OCR (bundled model) for PDF; direct DOCX text reading. Editable Word with pinyin. PDF: pixel-based bold/colour/highlight/underline. DOCX: direct run formatting. Approximate 11/9/7 page fit and up to 2 small-text region rescans.");
    Button pick=new Button(this);pick.setText("Choose PDF or DOCX");box.addView(pick);
    pick.setOnClickListener(v->{ Intent i=new Intent(Intent.ACTION_OPEN_DOCUMENT);i.addCategory(Intent.CATEGORY_OPENABLE);i.setType("*/*");i.putExtra(Intent.EXTRA_MIME_TYPES,new String[]{"application/pdf","application/vnd.openxmlformats-officedocument.wordprocessingml.document"});startActivityForResult(i,OPEN);});
    range=new EditText(this);range.setHint("Original pages e.g. 2-7 (blank = all)");range.setSingleLine(true);box.addView(range);
    start=new Button(this);start.setText("Convert offline");start.setEnabled(false);box.addView(start);
    message=new TextView(this);message.setText("Select a document");message.setTextSize(15);box.addView(message);
    save=new Button(this);save.setText("Save editable Word file");save.setEnabled(false);box.addView(save);
    start.setOnClickListener(v->convert());
    save.setOnClickListener(v->{Intent i=new Intent(Intent.ACTION_CREATE_DOCUMENT);i.addCategory(Intent.CATEGORY_OPENABLE);i.setType("application/vnd.openxmlformats-officedocument.wordprocessingml.document");i.putExtra(Intent.EXTRA_TITLE,"Chinese_Pinyin_Converted.docx");startActivityForResult(i,EXPORT);});
  }
  private void status(String s){runOnUiThread(()->message.setText(s));}
  @Override protected void onActivityResult(int req,int code,Intent data) {
    super.onActivityResult(req,code,data);if(code!=RESULT_OK||data==null||data.getData()==null)return;
    if(req==OPEN){source=data.getData();result=null;start.setEnabled(true);save.setEnabled(false);status("Document selected");}
    else if(req==EXPORT&&result!=null) {
      try(InputStream in=new FileInputStream(result);OutputStream out=getContentResolver().openOutputStream(data.getData())) {
        byte[] buf=new byte[32768];int n;while((n=in.read(buf))>=0)out.write(buf,0,n);
        status("Saved editable DOCX");
      }catch(Exception e){status("Save error: "+e.getMessage());}
    }
  }
  private void convert() {
    if(source==null)return;start.setEnabled(false);save.setEnabled(false);
    final Uri input=source;final String selection=range.getText().toString();
    worker.execute(()->{
      try{
        status("Loading document...");
        String mime=getContentResolver().getType(input);
        pageMarks.clear();docxMarks.clear();
        ArrayList<String> texts=new ArrayList<>();
        ArrayList<Integer> selected=new ArrayList<>();
        if("application/pdf".equals(mime)||looksLikePdf(input)){
          texts=ocrPdf(input,selection,selected);
        }else{
          ArrayList<String> pages=extractDocx(input);
          selected=select(selection,pages.size());
          for(int i:selected){texts.add(pages.get(i-1));pageMarks.add(docxMarks.get(i-1));}
        }
        if(selected.isEmpty())throw new IOException("No pages in selected range");
        status("Building editable DOCX...");
        File f=new File(getCacheDir(),"Chinese_Pinyin_Converted.docx");
        makeDocx(f,selected,texts,pageMarks);
        result=f;runOnUiThread(()->{start.setEnabled(true);save.setEnabled(true);});status("Converted "+selected.size()+" original pages. Tap Save.");
      }catch(Exception e){status("Conversion failed: "+e.getClass().getSimpleName()+": "+e.getMessage());runOnUiThread(()->start.setEnabled(true));}
    });
  }
  private boolean looksLikePdf(Uri uri){try(InputStream i=getContentResolver().openInputStream(uri)){byte[] b=new byte[5];return i!=null&&i.read(b)==5&&"%PDF-".equals(new String(b,StandardCharsets.US_ASCII));}catch(Exception e){return false;}}

  /** Offline OCR: the bundled Google ML Kit Chinese recognizer runs on-device. */
  private ArrayList<String> ocrPdf(Uri uri,String selection,ArrayList<Integer> selected)throws Exception {
    File local=new File(getCacheDir(),"input.pdf");
    try(InputStream in=getContentResolver().openInputStream(uri);OutputStream out=new FileOutputStream(local)){
      if(in==null)throw new IOException("Cannot open PDF");
      byte[] buf=new byte[65536];int n;while((n=in.read(buf))!=-1)out.write(buf,0,n);
    }
    ArrayList<String> texts=new ArrayList<>();
    com.google.mlkit.vision.text.TextRecognizer recognizer=
       com.google.mlkit.vision.text.TextRecognition.getClient(
          new com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions.Builder().build());
    try {
      try(ParcelFileDescriptor fd=ParcelFileDescriptor.open(local,ParcelFileDescriptor.MODE_READ_ONLY);
          PdfRenderer pdf=new PdfRenderer(fd)){
        selected.addAll(select(selection,pdf.getPageCount()));
        for(int sourcePage:selected){
          status("Offline ML Kit OCR: page "+sourcePage+" / "+pdf.getPageCount());
          try(PdfRenderer.Page page=pdf.openPage(sourcePage-1)){
            float scale=Math.min(2.0f,1900f/Math.max(page.getWidth(),page.getHeight()));
            int w=Math.max(1,Math.round(page.getWidth()*scale));
            int h=Math.max(1,Math.round(page.getHeight()*scale));
            Bitmap bmp=Bitmap.createBitmap(w,h,Bitmap.Config.ARGB_8888);
            try{
              bmp.eraseColor(Color.WHITE);
              page.render(bmp,null,null,PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);
              com.google.mlkit.vision.text.Text scan=
                  com.google.android.gms.tasks.Tasks.await(
                      recognizer.process(com.google.mlkit.vision.common.InputImage.fromBitmap(bmp,0)));
              ArrayList<com.google.mlkit.vision.text.Text.Line> lines=new ArrayList<>();
              for(com.google.mlkit.vision.text.Text.TextBlock block:scan.getTextBlocks())
                lines.addAll(block.getLines());
              Collections.sort(lines,(a,b)->{
                android.graphics.Rect ar=a.getBoundingBox(),br=b.getBoundingBox();
                if(ar==null||br==null)return 0;
                int d=ar.top-br.top;
                return Math.abs(d)>Math.max(9,Math.min(ar.height(),br.height())/2)?d:ar.left-br.left;
              });
              StringBuilder recognized=new StringBuilder();
              ArrayList<AndroidStyle.Mark> marks=new ArrayList<>();
              for(com.google.mlkit.vision.text.Text.Line line:lines){
                if(line.getText().trim().isEmpty())continue;
                recognized.append(line.getText()).append('\n');
                marks.addAll(AndroidStyle.inspectLine(bmp,line));
                marks.add(AndroidStyle.plain());
              }
              // Extra high-resolution region OCR: at most two scans, only for small OCR boxes.
              ArrayList<android.graphics.Rect> regions=smallRegions(lines,bmp);
              for(android.graphics.Rect region:regions){
                Bitmap small=Bitmap.createBitmap(bmp,region.left,region.top,region.width(),region.height());
                Bitmap enlarged=Bitmap.createScaledBitmap(small,small.getWidth()*2,small.getHeight()*2,true);
                try{
                  com.google.mlkit.vision.text.Text tiny=com.google.android.gms.tasks.Tasks.await(
                    recognizer.process(com.google.mlkit.vision.common.InputImage.fromBitmap(enlarged,0)));
                  for(com.google.mlkit.vision.text.Text.TextBlock block:tiny.getTextBlocks()){
                    for(com.google.mlkit.vision.text.Text.Line line:block.getLines()){
                      String found=line.getText().trim();
                      if(found.length()>1&&!recognized.toString().contains(found)){
                        recognized.append(found).append('\n');
                        for(int c=0;c<found.length()+1;c++)marks.add(AndroidStyle.plain());
                      }
                    }
                  }
                }finally{small.recycle();enlarged.recycle();}
              }
              texts.add(recognized.toString());
              pageMarks.add(marks);
            }finally{bmp.recycle();}
          }
        }
      }
    }finally{recognizer.close();}
    return texts;
  }

  /** Up to two genuinely small text boxes; skip normal-size blocks. */
  private ArrayList<android.graphics.Rect> smallRegions(
       ArrayList<com.google.mlkit.vision.text.Text.Line> lines,Bitmap bmp){
    ArrayList<Integer> heights=new ArrayList<>();
    for(com.google.mlkit.vision.text.Text.Line l:lines){
      android.graphics.Rect r=l.getBoundingBox();if(r!=null&&r.height()>1)heights.add(r.height());
    }
    ArrayList<android.graphics.Rect> regions=new ArrayList<>();
    if(heights.size()<3)return regions;
    Collections.sort(heights);int typical=heights.get(heights.size()/2);
    ArrayList<android.graphics.Rect> candidates=new ArrayList<>();
    for(com.google.mlkit.vision.text.Text.Line l:lines){
      android.graphics.Rect r=l.getBoundingBox();
      if(r==null||r.height()>=typical*.68f)continue;
      int padX=Math.max(10,r.width()/4),padY=Math.max(8,r.height()*2);
      android.graphics.Rect expanded=new android.graphics.Rect(
        Math.max(0,r.left-padX),Math.max(0,r.top-padY),
        Math.min(bmp.getWidth(),r.right+padX),Math.min(bmp.getHeight(),r.bottom+padY));
      if(expanded.width()>8&&expanded.height()>8)candidates.add(expanded);
    }
    Collections.sort(candidates,(a,b)->Integer.compare(a.height(),b.height()));
    for(android.graphics.Rect r:candidates){
      boolean overlaps=false;
      for(android.graphics.Rect chosen:regions){
        android.graphics.Rect inter=new android.graphics.Rect();
        if(inter.setIntersect(chosen,r)&&inter.width()*inter.height()>r.width()*r.height()*.5f){overlaps=true;break;}
      }
      if(!overlaps)regions.add(r);
      if(regions.size()>=2)break;
    }
    return regions;
  }
  private ArrayList<String> extractDocx(Uri uri)throws Exception{
    byte[] doc;
    try(InputStream in=getContentResolver().openInputStream(uri);ByteArrayOutputStream out=new ByteArrayOutputStream()){
      if(in==null)throw new IOException("Cannot open DOCX");
      byte[] b=new byte[32768];int n;while((n=in.read(b))!=-1)out.write(b,0,n);doc=out.toByteArray();
    }
    byte[] xml=null;
    try(ZipInputStream zin=new ZipInputStream(new ByteArrayInputStream(doc))){
      ZipEntry ent;while((ent=zin.getNextEntry())!=null){
        if(ent.getName().equals("word/document.xml")){ByteArrayOutputStream out=new ByteArrayOutputStream();byte[] buf=new byte[8192];int n;while((n=zin.read(buf))!=-1)out.write(buf,0,n);xml=out.toByteArray();break;}
      }
    }
    if(xml==null)throw new IOException("No word/document.xml in file");
    DocumentBuilderFactory f=DocumentBuilderFactory.newInstance();f.setNamespaceAware(true);
    // Android's Harmony XML parser rejects the Apache disallow-doctype feature.
    // Refuse DTD/ENTITY declarations explicitly instead of silently parsing them.
    String safeXml=new String(xml,StandardCharsets.UTF_8);
    if(safeXml.matches("(?is).*!\\s*DOCTYPE.*")||safeXml.matches("(?is).*!\\s*ENTITY.*"))
      throw new IOException("DOCX XML contains prohibited DTD/entity declarations");
    javax.xml.parsers.DocumentBuilder builder=f.newDocumentBuilder();
    builder.setEntityResolver((publicId,systemId)->new org.xml.sax.InputSource(new java.io.StringReader("")));
    Document d=builder.parse(new ByteArrayInputStream(xml));
    ArrayList<String> pages=new ArrayList<>();StringBuilder current=new StringBuilder();
    ArrayList<AndroidStyle.Mark> currentMarks=new ArrayList<>();
    NodeList paragraphs=d.getElementsByTagNameNS("http://schemas.openxmlformats.org/wordprocessingml/2006/main","p");
    for(int x=0;x<paragraphs.getLength();x++){
      Node p=paragraphs.item(x);NodeList kids=p.getChildNodes();
      for(int j=0;j<kids.getLength();j++)appendDocxText(kids.item(j),current,pages,currentMarks);
      current.append('\n');currentMarks.add(AndroidStyle.plain());
    }
    pages.add(current.toString());docxMarks.add(new ArrayList<>(currentMarks));
    // Word-rendered automatic page boundaries cannot be determined from DOCX XML.
    if(pages.size()==1)status("DOCX has no explicit page breaks; treating it as page 1.");
    return pages;
  }

  private void appendDocxText(Node node,StringBuilder current,ArrayList<String> pages,ArrayList<AndroidStyle.Mark> styles){
    String n=node.getLocalName();String ns=node.getNamespaceURI();
    if("http://schemas.openxmlformats.org/wordprocessingml/2006/main".equals(ns)){
      if("t".equals(n)){
        String t=node.getTextContent();current.append(t);
        AndroidStyle.Mark mark=AndroidStyle.parseRun(node);
        for(int k=0;k<t.length();k++)styles.add(mark.copy());
        return;
      }
      if("tab".equals(n)){current.append('\t');styles.add(AndroidStyle.plain());return;}
      if("br".equals(n)){
        Node typ=node.getAttributes()==null?null:node.getAttributes().getNamedItemNS(ns,"type");
        if(typ!=null&&"page".equals(typ.getNodeValue())){
          pages.add(current.toString());docxMarks.add(new ArrayList<>(styles));
          current.setLength(0);styles.clear();
        }else{current.append('\n');styles.add(AndroidStyle.plain());}
        return;
      }
      if("lastRenderedPageBreak".equals(n))return;
    }
    NodeList child=node.getChildNodes();
    for(int i=0;i<child.getLength();i++)appendDocxText(child.item(i),current,pages,styles);
  }
  private ArrayList<Integer> select(String raw,int max)throws IOException{
    LinkedHashSet<Integer> chosen=new LinkedHashSet<>();
    if(raw.trim().isEmpty()){for(int i=1;i<=max;i++)chosen.add(i);}
    else for(String token:raw.split(",")){
      String[] p=token.trim().split("-");
      if(p.length<1||p.length>2)throw new IOException("Invalid page range");
      int a=Integer.parseInt(p[0].trim()),b=p.length==2?Integer.parseInt(p[1].trim()):a;
      if(a<1||b<a||b>max)throw new IOException("Page "+token+" is outside 1-"+max);
      for(int i=a;i<=b;i++)chosen.add(i);
    }
    ArrayList<Integer> order=new ArrayList<>(chosen);Collections.sort(order);return order;
  }
  /** Convert arbitrary OCR text into valid XML 1.0 character data. */
  private static String escape(String source){
    StringBuilder out=new StringBuilder(source.length()+8);
    for(int i=0;i<source.length();){
      int cp=source.codePointAt(i);
      i+=Character.charCount(cp);
      boolean valid=cp==9||cp==10||cp==13||
        (cp>=32&&cp<=0xD7FF)||
        (cp>=0xE000&&cp<=0xFFFD)||
        (cp>=0x10000&&cp<=0x10FFFF);
      if(!valid){out.append('\uFFFD');continue;}
      if(cp=='&')out.append("&amp;");
      else if(cp=='<')out.append("&lt;");
      else if(cp=='>')out.append("&gt;");
      else if(cp=='"')out.append("&quot;");
      else out.appendCodePoint(cp);
    }
    return out.toString();
  }

  private static String run(String s,AndroidStyle.Mark m,int han){
    return "<w:r>"+AndroidStyle.rpr(m,han)+"<w:t xml:space=\"preserve\">"+escape(s)+"</w:t></w:r>";
  }
  private static String ruby(char c,String py,AndroidStyle.Mark mark,int han,int pin){
    return "<w:r><w:ruby><w:rubyPr><w:rubyAlign w:val=\"center\"/><w:hps w:val=\""+pin+"\"/><w:hpsRaise w:val=\""+(han+pin)+"\"/><w:hpsBaseText w:val=\""+han+"\"/></w:rubyPr>"
      +"<w:rt><w:r><w:rPr><w:sz w:val=\""+pin+"\"/></w:rPr><w:t>"+escape(py)+"</w:t></w:r></w:rt>"
      +"<w:rubyBase><w:r>"+AndroidStyle.rpr(mark,han)+"<w:t>"+escape(String.valueOf(c))+"</w:t></w:r></w:rubyBase></w:ruby></w:r>";
  }
  // Approximate Word page capacity. Never repeatedly open the Word layout engine.
  private static int[] fit(String text){
    int[][] options={{11,6},{9,5},{7,4}};
    for(int[] op:options){
      double charWidth=op[0]*1.05,usableWidth=465.0,usableHeight=675.0;
      int width=(int)(usableWidth/charWidth);
      int lines=0;
      for(String paragraph:text.split("\\n",-1))
        lines+=Math.max(1,(paragraph.length()+Math.max(1,width)-1)/Math.max(1,width));
      double lineHeight=op[0]+op[1]+6;
      if(lines*lineHeight<=usableHeight)return op;
    }
    return options[2];
  }
  private static final Map<Character,String> CACHE=new HashMap<>();
  private static String pinyin(char c){
    if(c<0x3400||c>0x9FFF)return "";
    String cached=CACHE.get(c);if(cached!=null)return cached;
    try{
      HanyuPinyinOutputFormat format=new HanyuPinyinOutputFormat();
      format.setToneType(HanyuPinyinToneType.WITH_TONE_MARK);
      format.setVCharType(HanyuPinyinVCharType.WITH_U_UNICODE);
      String[] p=PinyinHelper.toHanyuPinyinStringArray(c,format);
      String value=p!=null&&p.length>0?p[0]:"";
      CACHE.put(c,value);return value;
    }catch(Exception ex){return "";}
  }
  private static void zipText(ZipOutputStream zip,String name,String text)throws IOException{
    zip.putNextEntry(new ZipEntry(name));zip.write(text.getBytes(StandardCharsets.UTF_8));zip.closeEntry();
  }

  private void makeDocx(File file,ArrayList<Integer> nums,ArrayList<String> pages,
                        ArrayList<ArrayList<AndroidStyle.Mark>> marksByPage)throws Exception{
    StringBuilder body=new StringBuilder();
    for(int page=0;page<pages.size();page++){
      if(page>0)body.append("<w:p><w:r><w:br w:type=\"page\"/></w:r></w:p>");
      String data=pages.get(page);
      int[] sizes=fit(data);
      int han=sizes[0]*2,pin=sizes[1]*2;
      body.append("<w:p><w:pPr><w:jc w:val=\"left\"/></w:pPr>")
          .append(run(String.valueOf(nums.get(page)),AndroidStyle.plain(),han)).append("</w:p>");
      ArrayList<AndroidStyle.Mark> styles=page<marksByPage.size()?marksByPage.get(page):new ArrayList<>();
      body.append("<w:p><w:pPr><w:spacing w:line=\""+(han+pin+12)*10+"\" w:lineRule=\"atLeast\"/></w:pPr>");
      for(int i=0;i<data.length();){
        int cp=data.codePointAt(i);
        int offset=i;
        i+=Character.charCount(cp);
        if(cp=='\r')continue;
        if(cp=='\n'){body.append("</w:p><w:p><w:pPr><w:spacing w:line=\""+(han+pin+12)*10+"\" w:lineRule=\"atLeast\"/></w:pPr>");continue;}
        AndroidStyle.Mark style=offset<styles.size()?styles.get(offset):AndroidStyle.plain();
        String py=cp<=0xFFFF?pinyin((char)cp):"";
        String glyph=new String(Character.toChars(cp));
        body.append(py.isEmpty()?run(glyph,style,han):ruby((char)cp,py,style,han,pin));
      }
      body.append("</w:p>");
    }
    String doc="<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?><w:document xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\"><w:body>"+body+"<w:sectPr/></w:body></w:document>";
    String types="<?xml version=\"1.0\" encoding=\"UTF-8\"?><Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\"><Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/><Default Extension=\"xml\" ContentType=\"application/xml\"/><Override PartName=\"/word/document.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml\"/></Types>";
    String rels="<?xml version=\"1.0\" encoding=\"UTF-8\"?><Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\"><Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument\" Target=\"word/document.xml\"/></Relationships>";
    try(ZipOutputStream zip=new ZipOutputStream(new FileOutputStream(file))){
      zipText(zip,"[Content_Types].xml",types);
      zipText(zip,"_rels/.rels",rels);
      zipText(zip,"word/document.xml",doc);
    }
  }
}
