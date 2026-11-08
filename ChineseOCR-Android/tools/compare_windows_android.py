#!/usr/bin/env python3
"""Compare actual Windows MinerU 4 Standard ZIP with Android exported DOCX for the SAME PDF page.

Usage:
    python compare_windows_android.py page1_mineru_standard.zip page1_android.docx --minimum 97

Only Chinese ideographs are scored. Scores are agreement with the Windows
transcription, NOT absolute OCR accuracy; requires actual outputs, not source
application ZIPs. Requires Python 3.9+, no external packages.
"""
from __future__ import annotations
import argparse, json, zipfile
from difflib import SequenceMatcher
from pathlib import Path
from xml.etree import ElementTree as ET

W = '{http://schemas.openxmlformats.org/wordprocessingml/2006/main}'

def cjk(s):
    return ''.join(c for c in s if 0x3400 <= ord(c) <= 0x9fff or 0x20000 <= ord(c) <= 0x2fa1f)

def windows_text(path):
    with zipfile.ZipFile(path) as z:
        candidates = [s for s in z.namelist() if s == 'markdown.md' or s.endswith('/markdown.md')]
        if not candidates: raise ValueError('Not a MinerU one-pass output ZIP: markdown.md missing')
        return cjk(z.read(min(candidates,key=len)).decode('utf-8-sig'))

def android_text(path):
    with zipfile.ZipFile(path) as z:
        root = ET.fromstring(z.read('word/document.xml'))
    chars = []
    def visit(node, pinyin=False):
        pinyin = pinyin or node.tag == W+'rt'
        if node.tag == W+'t' and not pinyin: chars.append(node.text or '')
        for child in node: visit(child,pinyin)
    visit(root)
    return cjk(''.join(chars))

def lev(a,b):
    prev=list(range(len(b)+1))
    for i,x in enumerate(a,1):
        row=[i]
        for j,y in enumerate(b,1):
            row.append(min(row[-1]+1,prev[j]+1,prev[j-1]+int(x!=y)))
        prev=row
    return prev[-1]

def compare(a,b):
    n=lev(a,b)
    errors=[{'op':op,'windows':a[i:j],'android':b[k:l],'offset':i}
            for op,i,j,k,l in SequenceMatcher(None,a,b,autojunk=False).get_opcodes() if op != 'equal']
    return {'windows_chars':len(a),'android_chars':len(b),'edit_distance':n,
            'agreement_pct':round(max(0,100*(1-n/max(1,len(a)))),3),
            'differences':errors}

def main():
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument('mineru_windows_zip',type=Path)
    p.add_argument('android_export_docx',type=Path)
    p.add_argument('--minimum',type=float,default=97)
    p.add_argument('--save',type=Path,help='Optional output JSON report')
    a=p.parse_args()
    expected=windows_text(a.mineru_windows_zip)
    if not expected: p.error('Windows ZIP has no recognized Chinese text')
    score=compare(expected,android_text(a.android_export_docx))
    report=json.dumps(score,ensure_ascii=False,indent=2)
    print(report)
    if a.save: a.save.write_text(report,encoding='utf8')
    raise SystemExit(0 if score['agreement_pct']>=a.minimum else 1)

if __name__=='__main__':main()
