package com.example.chineseocr;

import android.app.Activity;
import android.os.Bundle;
import android.content.*;
import android.graphics.*;
import android.graphics.Rect;
import android.graphics.pdf.PdfRenderer;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.view.*;
import android.widget.*;
import com.google.android.gms.tasks.Tasks;
import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.text.Text;
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions;
import com.google.mlkit.vision.text.TextRecognition;
import com.google.mlkit.vision.text.TextRecognizer;
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
  private EditText range; private TextView message; private Button start,save;
  private static final int OPEN=1,EXPORT=2;
  @Override public void onCreate(Bundle b) {
    super.onCreate(b);
    LinearLayout box=new LinearLayout(this);box.setOrientation(1);box.setPadding(26,30,26,24);
    ScrollView sc=new ScrollView(this);sc.addView(box);setContentView(sc);
    TextView title=new TextView(this);title.setText("Chinese OCR + Pinyin");title.setTextSize(25);box.addView(title);
    TextView info=new TextView(this);info.setText("Offline PDF/DOCX converter • experimental Android build. OCR text and pronunciation can be incorrect. PDF bold and highlights are not retained.");box.addView(info);
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
        ArrayList<String> pages;
        if("application/pdf".equals(mime)||looksLikePdf(input))pages=ocrPdf(input);
        else pages=extractDocx(input);
        ArrayList<Integer> selected=select(selection,pages.size());
        if(selected.isEmpty())throw new IOException("No pages in selected range");
        ArrayList<String> texts=new ArrayList<>();
        for(int i=0;i<selected.size();i++)texts.add(pages.get(selected.get(i)-1));
        status("Building editable DOCX...");
        File f=new File(getCacheDir(),"Chinese_Pinyin_Converted.docx");
        makeDocx(f,selected,texts);
        result=f;runOnUiThread(()->{start.setEnabled(true);save.setEnabled(true);});status("Converted "+selected.size()+" original pages. Tap Save.");
      }catch(Exception e){status("Conversion failed: "+e.getClass().getSimpleName()+": "+e.getMessage());runOnUiThread(()->start.setEnabled(true));}
    });
  }
  private boolean looksLikePdf(Uri uri){try(InputStream i=getContentResolver().openInputStream(uri)){byte[] b=new byte[5];return i!=null&&i.read(b)==5&&"%PDF-".equals(new String(b,StandardCharsets.US_ASCII));}catch(Exception e){return false;}}
  private ArrayList<String> ocrPdf(Uri uri)throws Exception{
    File local=new File(getCacheDir(),"input.pdf");
    try(InputStream in=getContentResolver().openInputStream(uri);OutputStream out=new FileOutputStream(local)){
      if(in==null)throw new IOException("Cannot open PDF");byte[] buf=new byte[65536];int n;while((n=in.read(buf))!=-1)out.write(buf,0,n);
    }
    ArrayList<String> texts=new ArrayList<>();
    try(ParcelFileDescriptor fd=ParcelFileDescriptor.open(local,ParcelFileDescriptor.MODE_READ_ONLY);
        PdfRenderer pdf=new PdfRenderer(fd);
        TextRecognizer recognizer=TextRecognition.getClient(new ChineseTextRecognizerOptions.Builder().build())){
      for(int idx=0;idx<pdf.getPageCount();idx++){
        status("Scanning PDF page "+(idx+1)+" / "+pdf.getPageCount());
        try(PdfRenderer.Page page=pdf.openPage(idx)){
          float scale=Math.min(2.5f,2400f/Math.max(page.getWidth(),page.getHeight()));
          int w=Math.max(1,(int)(page.getWidth()*scale)),h=Math.max(1,(int)(page.getHeight()*scale));
          Bitmap bmp=Bitmap.createBitmap(w,h,Bitmap.Config.ARGB_8888);
          bmp.eraseColor(Color.WHITE);
          page.render(bmp,null,null,PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);
          Text t=Tasks.await(recognizer.process(InputImage.fromBitmap(bmp,0)));
          StringBuilder recognized=new StringBuilder();
          for(Text.TextBlock block:t.getTextBlocks()){
            for(Text.Line line:block.getLines()){
              String value=line.getText();
              if(value==null||value.trim().isEmpty())continue;
              Rect bounds=line.getBoundingBox();
              int flags=0;
              if(bounds!=null){
                if(underlined(bmp,bounds))flags|=2;
                if(yellowHighlight(bmp,bounds))flags|=4;
              }
              recognized.append('\uE000').append((char)('0'+flags)).append(value).append('\uE000').append('0').append('\n');
            }
          }
          texts.add(recognized.toString());
          bmp.recycle();
        }
      }
    }
    return texts;
  }
  // Estimated visual formatting for scanned images; never a guaranteed style match.
  private boolean underlined(Bitmap bitmap,Rect rect) {
    int w=bitmap.getWidth(),h=bitmap.getHeight();
    int l=Math.max(0,rect.left+3),r=Math.min(w-1,rect.right-3);
    if(r-l<40)return false;
    for(int y=Math.max(0,rect.bottom-2);y<Math.min(h,rect.bottom+9);y++){
      int dark=0,total=0;
      for(int x=l;x<r;x+=2){
        int c=bitmap.getPixel(x,y);
        int gray=(Color.red(c)+Color.green(c)+Color.blue(c))/3;
        if(gray<110)dark++; total++;
      }
      if(total>0&&(double)dark/total>0.77)return true;
    }
    return false;
  }
  private boolean yellowHighlight(Bitmap bitmap,Rect rect) {
    int l=Math.max(0,rect.left),r=Math.min(bitmap.getWidth(),rect.right);
    int t=Math.max(0,rect.top),b=Math.min(bitmap.getHeight(),rect.bottom);
    int sampled=0,yellow=0;
    for(int y=t;y<b;y+=4)for(int x=l;x<r;x+=4){
      int c=bitmap.getPixel(x,y);
      int red=Color.red(c),green=Color.green(c),blue=Color.blue(c);
      if(red>145&&green>125&&blue<Math.min(red,green)*0.73)yellow++;
      sampled++;
    }
    return sampled>0&&(double)yellow/sampled>0.12;
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
    f.setFeature("http://apache.org/xml/features/disallow-doctype-decl",true);
    Document d=f.newDocumentBuilder().parse(new ByteArrayInputStream(xml));
    ArrayList<String> pages=new ArrayList<>();StringBuilder current=new StringBuilder();
    NodeList paragraphs=d.getElementsByTagNameNS("http://schemas.openxmlformats.org/wordprocessingml/2006/main","p");
    for(int x=0;x<paragraphs.getLength();x++){
      Node p=paragraphs.item(x);NodeList kids=p.getChildNodes();
      for(int j=0;j<kids.getLength();j++)appendDocxText(kids.item(j),current,pages);
      current.append('\n');
    }
    pages.add(current.toString());
    // Word-rendered automatic page boundaries cannot be determined from DOCX XML.
    if(pages.size()==1)status("DOCX has no explicit page breaks; treating it as page 1.");
    return pages;
  }
  private void appendDocxText(Node node,StringBuilder current,ArrayList<String> pages){
    String n=node.getLocalName();String ns=node.getNamespaceURI();
    if("http://schemas.openxmlformats.org/wordprocessingml/2006/main".equals(ns)){
      if("r".equals(n)){
        int flags=0;
        NodeList children=node.getChildNodes();
        for(int k=0;k<children.getLength();k++){
          Node pr=children.item(k);
          if(!"rPr".equals(pr.getLocalName()))continue;
          NodeList props=pr.getChildNodes();
          for(int q=0;q<props.getLength();q++){
            Node attr=props.item(q);String prop=attr.getLocalName();
            Node val=attr.getAttributes()==null?null:attr.getAttributes().getNamedItemNS(ns,"val");
            String v=val==null?"":val.getNodeValue();
            if("b".equals(prop)&&!"0".equals(v)&&!"false".equals(v))flags|=1;
            if("u".equals(prop)&&!"none".equals(v))flags|=2;
            if("highlight".equals(prop)&&!"none".equals(v))flags|=4;
            if("shd".equals(prop)&&attr.getAttributes()!=null){
              Node fill=attr.getAttributes().getNamedItemNS(ns,"fill");
              if(fill!=null&&!"FFFFFF".equalsIgnoreCase(fill.getNodeValue())&&!"auto".equalsIgnoreCase(fill.getNodeValue()))flags|=4;
            }
          }
        }
        current.append('\uE000').append((char)('0'+flags));
        for(int k=0;k<children.getLength();k++)if(!"rPr".equals(children.item(k).getLocalName()))appendDocxText(children.item(k),current,pages);
        current.append('\uE000').append('0');
        return;
      }
      if("t".equals(n)){current.append(node.getTextContent());return;}
      if("tab".equals(n)){current.append('\t');return;}
      if("br".equals(n)){
        Node typ=node.getAttributes()==null?null:node.getAttributes().getNamedItemNS(ns,"type");
        if(typ!=null&&"page".equals(typ.getNodeValue())){pages.add(current.toString());current.setLength(0);}
        else current.append('\n');return;
      }
      if("lastRenderedPageBreak".equals(n)){/* Word's automatic pagination snapshot; not reliable to split */return;}
    }
    NodeList child=node.getChildNodes();
    for(int i=0;i<child.getLength();i++)appendDocxText(child.item(i),current,pages);
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
  private static String escape(String s){return s.replace("&","&amp;").replace("<","&lt;").replace(">","&gt;").replace("\"","&quot;");}
  private static String style(int flags){
    StringBuilder b=new StringBuilder();
    if((flags&1)!=0)b.append("<w:b/>");
    if((flags&2)!=0)b.append("<w:u w:val=\"single\"/>");
    if((flags&4)!=0)b.append("<w:highlight w:val=\"yellow\"/>");
    return b.toString();
  }
  private static String run(String s){return run(s,0);}
  private static String run(String s,int flags){
    return "<w:r><w:rPr>"+style(flags)+"</w:rPr><w:t xml:space=\"preserve\">"+escape(s)+"</w:t></w:r>";
  }
  private static String ruby(String s,String py,int flags){
    String size="<w:sz w:val=\"11\"/>";
    return "<w:ruby><w:rubyPr><w:rubyAlign w:val=\"center\"/><w:hps w:val=\"11\"/><w:hpsRaise w:val=\"22\"/><w:hpsBaseText w:val=\"20\"/></w:rubyPr><w:rt><w:r><w:rPr>"+size+"</w:rPr><w:t>"+escape(py)+"</w:t></w:r></w:rt><w:rubyBase><w:r><w:rPr>"+style(flags)+"</w:rPr><w:t>"+escape(s)+"</w:t></w:r></w:rubyBase></w:ruby>";
  }
  private static String pinyin(char c){
    try{
      HanyuPinyinOutputFormat format=new HanyuPinyinOutputFormat();
      format.setToneType(HanyuPinyinToneType.WITH_TONE_MARK);
      format.setVCharType(HanyuPinyinVCharType.WITH_U_UNICODE);
      String[] p=PinyinHelper.toHanyuPinyinStringArray(c,format);
      return p!=null&&p.length>0?p[0]:"";
    }catch(Exception ex){return "";}
  }
  private static void zipText(ZipOutputStream zip,String name,String text)throws IOException{
    zip.putNextEntry(new ZipEntry(name));zip.write(text.getBytes(StandardCharsets.UTF_8));zip.closeEntry();
  }
  private void makeDocx(File file,ArrayList<Integer> nums,ArrayList<String> pages)throws Exception{
    StringBuilder body=new StringBuilder();
    for(int page=0;page<pages.size();page++){
      if(page>0)body.append("<w:p><w:r><w:br w:type=\"page\"/></w:r></w:p>");
      body.append("<w:p><w:pPr><w:jc w:val=\"left\"/></w:pPr>").append(run(String.valueOf(nums.get(page)))).append("</w:p>");
      String[] lines=pages.get(page).split("\\R",-1);
      for(String line:lines){
        body.append("<w:p><w:pPr><w:spacing w:before=\"30\" w:after=\"30\" w:line=\"600\" w:lineRule=\"atLeast\"/></w:pPr>");
        int active=0;
        for(int i=0;i<line.length();i++){
          char c=line.charAt(i);
          if(c=='\uE000'&&i+1<line.length()){active=Math.max(0,Math.min(7,line.charAt(++i)-'0'));continue;}
          String py=pinyin(c);
          body.append(py.isEmpty()?run(String.valueOf(c),active):ruby(String.valueOf(c),py,active));
        }
        body.append("</w:p>");
      }
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
