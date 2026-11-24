#!/usr/bin/env python3
from pathlib import Path
import urllib.request, re, zipfile, hashlib
root=Path('bundle'); root.mkdir(exist_ok=True)
name='wasm-stream-zh-int8'
hf='https://huggingface.co/datasets/jiangzhuo9357/sherpa-onnx-asr-models/resolve/main'
demo='https://huggingface.co/spaces/jiangzhuo9357/sherpa-onnx-asr-demos/resolve/main/streaming.html'
files=['sherpa-onnx-wasm-main-asr.js','sherpa-onnx-wasm-main-asr.wasm','sherpa-onnx-wasm-main-asr.data','sherpa-onnx-asr.js']
def fetch(url,path):
    print('GET',url,flush=True)
    req=urllib.request.Request(url,headers={'User-Agent':'Mozilla/5.0'})
    with urllib.request.urlopen(req,timeout=180) as i,path.open('wb') as o:
        while True:
            c=i.read(1048576)
            if not c: break
            o.write(c)
    n=path.stat().st_size
    print('OK',path,n,flush=True)
    if n<1000: raise RuntimeError('Bad download '+url)
    return n
fetch(demo,root/'index.html')
page=(root/'index.html').read_text(encoding='utf-8')
print('CDN BASE SOURCE:')
for line in page.splitlines():
    if any(x in line for x in ('CDN_BASE','URLSearchParams','stream-zh-int8')):
        print(line[:260],flush=True)
page,n=re.subn(r'(?m)^(\s*(?:const|let|var)\s+CDN_BASE\s*=).*?;',r'\1 "./";',page,count=1,flags=re.S)
if n!=1:raise RuntimeError('Failed local-only CDN_BASE substitution; upstream source changed')
if '</head>' not in page:raise RuntimeError('Missing </head>')
page=page.replace('</head>',"""<script>
if (!new URLSearchParams(location.search).has('model')) {
  location.replace(location.pathname+'?model=stream-zh-int8');
}
</script>
</head>""",1)
page=page.replace('Streaming ASR Browser Demo','Live Chinese Offline — Mandarin (WASM)')
(root/'index.html').write_text(page,encoding='utf-8')
model=root/name; model.mkdir(exist_ok=True)
for f in files:
    n=fetch(f'{hf}/{name}/{f}',model/f)
    if f.endswith('.data') and n<20000000: raise RuntimeError('Incomplete model')
    if f.endswith('.wasm') and n<1000000: raise RuntimeError('Incomplete runtime')
(root/'README.txt').write_text("""Live Chinese Offline — HTML + WebAssembly Mandarin model — EXPERIMENTAL

Unzip the entire package. Keep index.html and wasm-stream-zh-int8 folder together.
Uses a real local Chinese streaming Zipformer INT8 speech recognition model.
No Web Speech API, no model download, no cloud speech recognition required once downloaded.
Try on a PC first:
  cd extracted-folder
  python3 -m http.server 8000
  Open http://localhost:8000 in Chrome.
Microphone permission normally requires HTTPS or localhost. Directly opening
file:///Downloads/index.html on Android may FAIL because browsers block
file-origin XMLHttpRequests used to load the .wasm and .data files.
On Android use an HTTPS host with offline cache or a local localhost server.
This is NOT guaranteed to work from a lone file:/// HTML page.
The 59 MB 2023 Mandarin model has UNSTATED upstream redistribution licensing
and may run slowly or fail on a low-RAM Moto e13 browser.
Source of demo: https://huggingface.co/spaces/jiangzhuo9357/sherpa-onnx-asr-demos
Source of model: https://huggingface.co/datasets/jiangzhuo9357/sherpa-onnx-asr-models
Native inference: https://github.com/k2-fsa/sherpa-onnx
""")
(root/'start-server.py').write_text("from http.server import ThreadingHTTPServer,SimpleHTTPRequestHandler\nprint('Open http://localhost:8000')\nThreadingHTTPServer(('127.0.0.1',8000),SimpleHTTPRequestHandler).serve_forever()\n")
out=Path('LiveChineseOffline-HTML-WASM-Mandarin-Test.zip')
with zipfile.ZipFile(out,'w',compression=zipfile.ZIP_DEFLATED,compresslevel=3,allowZip64=True) as z:
    for f in root.rglob('*'):
        if f.is_file():z.write(f,f.relative_to(root))
print('ZIP',out.stat().st_size,hashlib.sha256(out.read_bytes()).hexdigest(),flush=True)
