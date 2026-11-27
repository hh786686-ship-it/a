import json, urllib.request
from pathlib import Path
repo="netease-youdao/Confucius4-R2T2-GGUF"
data=json.load(urllib.request.urlopen("https://huggingface.co/api/models/"+repo+"?blobs=true",timeout=60))
revision=data["sha"]
names=["Confucius4-R2T2-Q4_K_M.gguf","mmproj-Confucius4-R2T2-Q8_0.gguf"]
lines=[]
for i,name in enumerate(names):
    f=next(x for x in data["siblings"] if x["rfilename"]==name)
    lfs=f["lfs"]
    assert len(lfs["sha256"])==64 and lfs["size"]>100000000
    prefix="model" if i==0 else "projector"
    lines += [f"const {prefix}File = '{name}';",f"const {prefix}Bytes = {lfs['size']};",f"const {prefix}Hash = '{lfs['sha256']}';",f"const {prefix}Url = 'https://huggingface.co/{repo}/resolve/{revision}/{name}?download=true';"]
Path("lib/model_metadata.dart").write_text("\n".join(lines)+"\n")
