package com.example.chineseocr;

import java.util.*;

/** Offline phrase-level corrections for common Mandarin heteronyms.
 * This is a deterministic fallback, not a claim of universal contextual accuracy.
 */
final class PinyinContext {
  private static final Map<String,String[]> PHRASES = new HashMap<>();
  static {
    add("银行","yín háng"); add("行长","háng zhǎng"); add("行业","háng yè");
    add("行为","xíng wéi"); add("行走","xíng zǒu"); add("旅行","lǚ xíng");
    add("音乐","yīn yuè"); add("乐器","yuè qì"); add("快乐","kuài lè");
    add("乐观","lè guān"); add("重庆","chóng qìng"); add("重复","chóng fù");
    add("重新","chóng xīn"); add("重要","zhòng yào"); add("重量","zhòng liàng");
    add("长大","zhǎng dà"); add("长高","zhǎng gāo"); add("校长","xiào zhǎng");
    add("长城","cháng chéng"); add("长度","cháng dù"); add("长江","cháng jiāng");
    add("头发","tóu fà"); add("理发","lǐ fà"); add("发现","fā xiàn");
    add("发展","fā zhǎn"); add("出发","chū fā"); add("地方","dì fāng");
    add("土地","tǔ dì"); add("得到","dé dào"); add("觉得","jué de");
    add("跑得快","pǎo de kuài"); add("便宜","pián yi"); add("方便","fāng biàn");
    add("爱好","ài hào"); add("好奇","hào qí"); add("好人","hǎo rén");
    add("为了","wèi le"); add("因为","yīn wèi"); add("还钱","huán qián");
    add("归还","guī huán"); add("还是","hái shì"); add("还有","hái yǒu");
    add("看着","kàn zhe"); add("听着","tīng zhe"); add("睡着","shuì zháo");
    add("着火","zháo huǒ"); add("着急","zháo jí"); add("着陆","zhuó lù");
    add("干净","gān jìng"); add("干杯","gān bēi"); add("干活","gàn huó");
    add("能干","néng gàn"); add("难过","nán guò"); add("困难","kùn nan");
    add("灾难","zāi nàn"); add("数字","shù zì"); add("数学","shù xué");
    add("数一数","shǔ yi shǔ"); add("数数","shǔ shù");
  }
  private static void add(String word,String readings){
    String[] parts=readings.split(" ");
    if(word.codePointCount(0,word.length())!=parts.length)
      throw new IllegalArgumentException("Phrase pinyin length mismatch: "+word);
    PHRASES.put(word,parts);
  }
  /** Returns per-UTF16-offset overrides, leaving unmatched characters for the base dictionary. */
  static Map<Integer,String> overrides(String text){
    Map<Integer,String> result=new HashMap<>();
    for(int i=0;i<text.length();){
      String best=null;
      for(String phrase:PHRASES.keySet()){
        if(text.startsWith(phrase,i)&&(best==null||phrase.length()>best.length()))best=phrase;
      }
      if(best!=null){
        String[] syllables=PHRASES.get(best);
        int offset=i;
        for(String reading:syllables){
          result.put(offset,reading);
          offset+=Character.charCount(text.codePointAt(offset));
        }
        i+=best.length();
      }else i+=Character.charCount(text.codePointAt(i));
    }
    return result;
  }
  private PinyinContext(){}
}
