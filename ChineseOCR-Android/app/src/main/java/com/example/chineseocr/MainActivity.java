package com.example.chineseocr;

import android.app.Activity;
import android.os.Bundle;
import android.content.*;
import android.graphics.*;
import android.graphics.Rect;
import ai.onnxruntime.*;
import java.nio.FloatBuffer;
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
  private EditText range; private TextView message; private Button start,save; private CheckBox useMineru;
  private static final int OPEN=1,EXPORT=2;
  @Override public void onCreate(Bundle b) {
    super.onCreate(b);
    LinearLayout box=new LinearLayout(this);box.setOrientation(1);box.setPadding(26,30,26,24);
    ScrollView sc=new ScrollView(this);sc.addView(box);setContentView(sc);
    TextView title=new TextView(this);title.setText("Chinese OCR + Pinyin");title.setTextSize(25);box.addView(title);
    TextView info=new TextView(this);info.setText("Offline PDF/DOCX OCR. MinerU 4 ONNX is available for PDF conversion (experimental). ML Kit remains optional. Scanned styles and OCR accuracy require verification.");box.addView(info);
    Button pick=new Button(this);pick.setText("Choose PDF or DOCX");box.addView(pick);
    pick.setOnClickListener(v->{ Intent i=new Intent(Intent.ACTION_OPEN_DOCUMENT);i.addCategory(Intent.CATEGORY_OPENABLE);i.setType("*/*");i.putExtra(Intent.EXTRA_MIME_TYPES,new String[]{"application/pdf","application/vnd.openxmlformats-officedocument.wordprocessingml.document"});startActivityForResult(i,OPEN);});
    range=new EditText(this);range.setHint("Original pages e.g. 2-7 (blank = all)");range.setSingleLine(true);box.addView(range);
    Button mineruTest=new Button(this);
    mineruTest.setText("Test MinerU OCR models (offline)");
    box.addView(mineruTest);
    mineruTest.setOnClickListener(v->{
      mineruTest.setEnabled(false);
      worker.execute(()->{
        try{testMinerUModels();}
        catch(Throwable e){status("MinerU model test failed: "+e.getClass().getSimpleName()+": "+e.getMessage());}
        finally{runOnUiThread(()->mineruTest.setEnabled(true));}
      });
    });
    useMineru=new CheckBox(this);
    useMineru.setText("Use MinerU 4 OCR for PDF (experimental; offline)");
    useMineru.setChecked(true);
    box.addView(useMineru);
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
  // MinerU 4 PP-OCRv6 detector and recogniser are bundled from the official ONNX
  // model bundle. This is a device inference feasibility test, not an OCR pipeline.
  private void testMinerUModels()throws Exception{
    String[] names={"mineru-det.onnx","mineru-rec.onnx"};
    long[][] inputs={{1,3,256,256},{1,3,48,320}};
    OrtEnvironment env=OrtEnvironment.getEnvironment();
    StringBuilder report=new StringBuilder("MinerU 4 ONNX test:\n");
    for(int i=0;i<names.length;i++){
      status("Testing MinerU "+(i==0?"text detector":"text recogniser")+"...");
      File file=new File(getCacheDir(),names[i]);
      if(!file.isFile()||file.length()==0){
        try(InputStream src=getAssets().open(names[i]);OutputStream dst=new FileOutputStream(file)){
          byte[] buf=new byte[32768];int n;
          while((n=src.read(buf))!=-1)dst.write(buf,0,n);
        }
      }
      long startMs=android.os.SystemClock.elapsedRealtime();
      try(OrtSession.SessionOptions options=new OrtSession.SessionOptions()){
        options.setIntraOpNumThreads(2);
        try(OrtSession session=env.createSession(file.getAbsolutePath(),options)){
          long[] shape=inputs[i]; int size=1;for(long v:shape)size*=v;
          try(OnnxTensor input=OnnxTensor.createTensor(env,FloatBuffer.wrap(new float[size]),shape);
              OrtSession.Result out=session.run(Collections.singletonMap("x",input))){
            long elapsed=android.os.SystemClock.elapsedRealtime()-startMs;
            report.append(i==0?"Detector":"Recogniser").append(": executed, ")
              .append(elapsed).append(" ms, output count ")
              .append(out.size()).append('\n');
          }
        }
      }
    }
    report.append("This tests model execution only. PDF conversion still uses ML Kit.");
    status(report.toString());
  }
  private void convert() {
    if(source==null)return;start.setEnabled(false);save.setEnabled(false);
    final Uri input=source;final String selection=range.getText().toString();final boolean mineru=useMineru.isChecked();
    worker.execute(()->{
      try{
        status("Loading document...");
        String mime=getContentResolver().getType(input);
        ArrayList<String> pages;
        if("application/pdf".equals(mime)||looksLikePdf(input))pages=ocrPdf(input,mineru);
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
  private ArrayList<String> ocrPdf(Uri uri,boolean useMineruEngine)throws Exception{
    File local=new File(getCacheDir(),"input.pdf");
    try(InputStream in=getContentResolver().openInputStream(uri);OutputStream out=new FileOutputStream(local)){
      if(in==null)throw new IOException("Cannot open PDF");byte[] buf=new byte[65536];int n;while((n=in.read(buf))!=-1)out.write(buf,0,n);
    }
    ArrayList<String> texts=new ArrayList<>();
    File det=new File(getCacheDir(),"mineru-det.onnx");
    File rec=new File(getCacheDir(),"mineru-rec.onnx");
    MineruOcr mineru=null;
    try{
      if(useMineruEngine){
        status("Loading MinerU 4 ONNX OCR models...");
        copyModelIfNeeded("mineru-det.onnx",det);
        copyModelIfNeeded("mineru-rec.onnx",rec);
        mineru=new MineruOcr(det,rec,getAssets().open("ppocrv6_dict.txt"));
      }
      try(ParcelFileDescriptor fd=ParcelFileDescriptor.open(local,ParcelFileDescriptor.MODE_READ_ONLY);
          PdfRenderer pdf=new PdfRenderer(fd);
          TextRecognizer mlkit=useMineruEngine?null:TextRecognition.getClient(new ChineseTextRecognizerOptions.Builder().build())){
        for(int idx=0;idx<pdf.getPageCount();idx++){
          final int sourcePage=idx+1;
          status((useMineruEngine?"MinerU":"ML Kit")+" scanning page "+sourcePage+" / "+pdf.getPageCount());
          try(PdfRenderer.Page page=pdf.openPage(idx)){
            float scale=Math.min(2.2f,2400f/Math.max(page.getWidth(),page.getHeight()));
            int w=Math.max(1,(int)(page.getWidth()*scale)),h=Math.max(1,(int)(page.getHeight()*scale));
            Bitmap bmp=Bitmap.createBitmap(w,h,Bitmap.Config.ARGB_8888);
            bmp.eraseColor(Color.WHITE);
            page.render(bmp,null,null,PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);
            StringBuilder recognized=new StringBuilder();
            if(useMineruEngine){
              ArrayList<MineruOcr.Line> lines=mineru.read(bmp,message->status("Page "+sourcePage+": "+message));
              for(MineruOcr.Line line:lines){
                WordStyles.Style style=estimateStyle(bmp,line.bounds);
                recognized.append(WordStyles.begin(style)).append(line.text)
                  .append(WordStyles.end()).append('\n');
              }
            }else{
              Text t=Tasks.await(mlkit.process(InputImage.fromBitmap(bmp,0)));
              for(Text.TextBlock block:t.getTextBlocks()){
                for(Text.Line line:block.getLines()){
                  for(Text.Element element:line.getElements()){
                    String value=element.getText();
                    if(value==null||value.isEmpty())continue;
                    WordStyles.Style style=estimateStyle(bmp,element.getBoundingBox());
                    recognized.append(WordStyles.begin(style)).append(value)
                      .append(WordStyles.end()).append(' ');
                  }
                  recognized.append('\n');
                }
              }
            }
            texts.add(recognized.toString());
            bmp.recycle();
          }
        }
      }
    }finally{
      if(mineru!=null)mineru.close();
    }
    return texts;
  }
  private void copyModelIfNeeded(String name,File dest)throws IOException{
    if(dest.exists()&&dest.length()>1000000)return;
    try(InputStream input=getAssets().open(name);OutputStream out=new FileOutputStream(dest)){
      byte[] buf=new byte[65536];int n;while((n=input.read(buf))!=-1)out.write(buf,0,n);
    }
  }
  // The pixel heuristic is shared with a pure-Java test harness.
  private WordStyles.Style estimateStyle(Bitmap bitmap,Rect rect){
    if(rect==null)return WordStyles.Style.normal();
    return PixelWordStyle.estimate(new PixelWordStyle.Pixels(){
      public int width(){return bitmap.getWidth();}
      public int height(){return bitmap.getHeight();}
      public int argb(int x,int y){return bitmap.getPixel(x,y);}
    },rect.left,rect.top,rect.right,rect.bottom);
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
        WordStyles.Style style=WordStyles.fromRun(node);
        current.append(WordStyles.begin(style));
        NodeList children=node.getChildNodes();
        for(int k=0;k<children.getLength();k++){
          Node part=children.item(k);
          if("rPr".equals(part.getLocalName()))continue;
          int oldPageCount=pages.size();
          appendDocxText(part,current,pages);
          // A page break can sit *inside* a styled run. Reopen that
          // style on the following source page rather than dropping it.
          if(pages.size()>oldPageCount)current.append(WordStyles.begin(style));
        }
        current.append(WordStyles.end());
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
  private static String run(String s){
    return "<w:r><w:t xml:space=\"preserve\">"+WordStyles.escape(s)+"</w:t></w:r>";
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
      WordStyles.appendText(body,pages.get(page),MainActivity::pinyin);
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
