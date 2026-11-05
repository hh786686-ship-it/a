import sys, os
import numpy as np
import onnxruntime as ort
from PIL import Image, ImageDraw, ImageFont

assets='ChineseOCR-Android/app/src/main/assets'
det=ort.InferenceSession(assets+'/mineru-det.onnx',providers=['CPUExecutionProvider'])
rec=ort.InferenceSession(assets+'/mineru-rec.onnx',providers=['CPUExecutionProvider'])
vocab=open(assets+'/ppocrv6_dict.txt',encoding='utf8').read().splitlines()+[' ']
assert len(vocab)+1 == 18710
font_paths=['/usr/share/fonts/opentype/noto/NotoSansCJK-Regular.ttc','/usr/share/fonts/truetype/wqy/wqy-microhei.ttc']
font_path=next((p for p in font_paths if os.path.isfile(p)),None)
assert font_path,'Chinese font not found on runner'
font=ImageFont.truetype(font_path,44)
source='中文测试文字'
im=Image.new('RGB',(560,90),'white')
ImageDraw.Draw(im).text((14,15),source,font=font,fill=(0,0,0))
im.save('/tmp/mineru_ocr_test.png')
width=448;height=96
resized=np.asarray(im.resize((width,height)),dtype=np.float32)[:,:,::-1] /255.
mean=np.array([0.485,0.456,0.406],dtype=np.float32)
std=np.array([0.229,0.224,0.225],dtype=np.float32)
input=np.transpose((resized-mean)/std,(2,0,1))[None,:,:,:].astype('float32')
detout=det.run(None,{'x':input})[0]
assert detout.shape[0]==1 and detout.shape[1]==1
peak=float(detout.max())
print('DETECT_OUTPUT_SHAPE',detout.shape,'PEAK_SCORE',peak)
assert np.isfinite(detout).all()
assert peak>0.30, 'No Chinese text detected'

# Recognize a known clean synthetic line through the real PP-OCRv6 model.
width=448
rgb=np.asarray(im.resize((width,48)),dtype=np.float32)[:,:,::-1]/255.
recin=np.transpose((rgb-0.5)/0.5,(2,0,1))[None,:,:,:].astype('float32')
pred=rec.run(None,{'x':recin})[0]
print('RECOGNIZER_OUTPUT_SHAPE',pred.shape)
assert pred.shape[-1]==18710
ids=pred[0].argmax(axis=-1)
out=[]
prev=-1
for k in ids:
  k=int(k)
  if k!=0 and k!=prev and k<=len(vocab):
    out.append(vocab[k-1])
  prev=k
recognised=''.join(out)
print('CHINESE_GROUND_TRUTH',source)
print('CHINESE_RECOGNISED',recognised)
assert any(c in recognised for c in source), 'The OCR output contains none of the test Chinese characters'
print('PASS REAL MINERU MODEL INFERENCE AND CTC CHINESE DECODING')
