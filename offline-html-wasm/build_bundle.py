#!/usr/bin/env python3
"""Package official sherpa-onnx bilingual Chinese/English WASM browser ASR demo."""
from pathlib import Path
import urllib.request, json, zipfile, hashlib, re
root=Path('bundle'); root.mkdir(exist_ok=True)
name='k2-fsa/web-assembly-asr-sherpa-onnx-zh-en'
base='https://huggingface.co/spaces/'+name+'/resolve/main/'
api='https://huggingface.co/api/spaces/'+name+'/tree/main?recursive=true'
def request(url):
    return urllib.request.Request(url, headers={'User-Agent':'Mozilla/5.0'})
print('SPACE',name,flush=True)
with urllib.request.urlopen(request(api),timeout=60) as r:
    tree=json.load(r)
print('SPACE_TREE',[(x.get('path'),x.get('size')) for x in tree][:100],flush=True)
files=[]
for item in tree:
    path=item.get('path','')
    if item.get('type')!='file' or '..' in path or path.startswith('.'): continue
    if path.lower().endswith(('.html','.js','.css','.wasm','.data','.json','.txt','.svg','.png','.ico','.webp','.wav')):
        files.append(path)
assert files, 'No browser resources found in official Space'
def fetch(url,dest):
    dest.parent.mkdir(parents=True,exist_ok=True)
    print('GET',url,flush=True)
    with urllib.request.urlopen(request(url),timeout=300) as r, dest.open('wb') as o:
        while True:
            b=r.read(1024*1024)
            if not b:break
            o.write(b)
    size=dest.stat().st_size
    print('DOWNLOADED',dest,size,flush=True)
    assert size>0,'Empty file '+str(dest)
for file in files:
    fetch(base+file,root/file)
webpages=[p for p in root.glob('*.html') if p.name=='index.html']
assert webpages,'No index.html in official Space'
model_files=list(root.rglob('*.data'))
wasm_files=list(root.rglob('*.wasm'))
assert model_files and wasm_files, 'Missing WASM or model .data'
assert max(p.stat().st_size for p in model_files)>20_000_000,'Data too small'
assert max(p.stat().st_size for p in wasm_files)>1_000_000,'WASM too small'
print('MAIN_HTML_SOURCE', (root/'index.html').read_text(errors='replace')[:3500],flush=True)
(root/'README.txt').write_text("""EXPERIMENTAL OFFLINE MANDARIN HTML + WASM
Official sherpa-onnx WebAssembly browser recognizer (Chinese + English).
Contains actual model data (.data), WebAssembly interpreter (.wasm),
JavaScript runtime and index.html. No remote speech service.

HOW TO TRY:
1. Extract the entire ZIP. Keep all files and folders together.
2. On a computer, open a terminal in the extracted folder.
3. Run: python3 -m http.server 8000
4. Visit: http://localhost:8000/ in Chrome; allow microphone.
5. Disconnect the internet; local model inference should still work.

ANDROID LIMITATION:
Opening index.html directly from an Android Downloads file:// URL can
block WASM model fetches and microphone permissions. Chrome generally requires
HTTPS or an HTTP localhost server. This package does NOT automatically solve
those browser restrictions: a trusted localhost server or a securely hosted PWA
is needed for reliable Android microphone use.
Low-memory Moto e13 speed and RAM are not tested. Entire model may require
hundreds of MB RAM on top of the files on disk.
The demo source and model are provided by the sherpa-onnx project:
https://huggingface.co/spaces/k2-fsa/web-assembly-asr-sherpa-onnx-zh-en
https://github.com/k2-fsa/sherpa-onnx
Use for personal testing and observe upstream license terms.
""")
(root/'start-local-server.py').write_text("from http.server import ThreadingHTTPServer,SimpleHTTPRequestHandler\nprint('Open http://localhost:8000')\nThreadingHTTPServer(('127.0.0.1',8000),SimpleHTTPRequestHandler).serve_forever()\n")
out=Path('LiveChineseOffline-HTML-WASM-Mandarin-Test.zip')
with zipfile.ZipFile(out,'w',compression=zipfile.ZIP_DEFLATED,compresslevel=3,allowZip64=True) as z:
    for p in root.rglob('*'):
        if p.is_file():z.write(p,p.relative_to(root))
print('ZIP',out.stat().st_size,hashlib.sha256(out.read_bytes()).hexdigest(),flush=True)
