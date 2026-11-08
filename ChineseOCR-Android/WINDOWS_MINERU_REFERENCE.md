# Exact Windows MinerU OCR reference (user-supplied ZIP)

Source inspected: `MinerU_FAST_MinerU_Only_Underline_OnePass_TESTED(1).zip`,
directory `MinerU_Fast_Styled_v1_Windows/`.

## What the Windows app REALLY runs

`app.py::pdf_to_blocks()` executes, for **each selected PDF page**:

```shell
mineru-kit parse page_N.pdf -o page_N.zip --format zip --tier standard --ocr-mode ocr
```

`mineru_output.py::read_one_pass()` reads **both**
`markdown.md` and `middle_json.json` from that same ZIP. Markdown
supplies the recognized text; Middle JSON supplies page/line/position metadata
for `style_transfer.py`, `bold_mineru.py`, underline alignment and
`small_text_recovery.py`.

The installer requests `mineru>=4.0,<5` under **Python 3.12**, but
does not pin an exact 4.x version or model checksum. The Windows ZIP
contains **application source**, not the actual installed MinerU models,
a Python environment, or a sample verified MinerU output ZIP.

**Important:** `--tier standard` is not the same as the 22 MB OCR detector +
recognizer currently bundled in the Android experimental APK. MinerU 4 Standard
uses the full document parser with small models plus a vision-language model.
Bundling those two ONNX models cannot create exact parity.

## Important Windows reference behaviors

- Original source PDF is sliced to individual pages with PyMuPDF.
- One full-page Standard MinerU call per page, forcibly OCR mode.
- OCR Markdown is normalized into plain text and style spans.
- Middle JSON spans and lines determine text placement and underline/bold mapping.
- Up to **two extra region-only MinerU parses** for missing small text (not
  more full-page OCR calls).
- **pinyin-pro** JavaScript worker supplies context-dependent per-character pinyin.
- Word pages are fitted 11/6 -> 9/5 -> 7/4 Chinese/pinyin pt and validated
  against actual Microsoft Word pagination where possible.

## Missing from current Android implementation

1. MinerU 4 Standard VLM and document-layout pipeline.
2. Official model-specific OCR box postprocessing and box rectification.
3. Standard-tier Markdown/Middle JSON rendering and aligned positioned spans.
4. Matching Windows small-text proposal: OpenCV adaptive threshold,
   horizontal close, component sizes, geometry exclusion and ≤2 region parses.
5. Windows per-character medial-axis stroke diameter bold mechanism.
6. Pinyin-pro worker and MS Word page-count validation.

The Android application currently provides an **experimental ONNX OCR subset**.
Do not label it “same Windows MinerU pipeline” or assert equivalent accuracy.

## Acceptance tests before claiming parity

- Record installed `mineru-kit` version and model configuration on Windows.
- Parse identical **real scanned PDF pages** through each implementation.
- Use strict CJK-character edit distance; compare source output per page, including
  repeated text, missing characters and reading order.
- Compare the Windows ZIP Markdown / Middle JSON to Android text/geometry.
- Review formatted source PDF vs exported DOCX for bold, colour, highlight,
  underline and original-page pagination; score errors separately.
- Independently open exported DOCX using Word or a desktop renderer.
- Confirm no internet is required at Android runtime; benchmark on the actual A16.
- Do not release as equivalent before these checks pass. Even a perfect match to
  Windows OCR does not prove Windows OCR itself is 100% accurate.

## Options if faithful Standard cannot run on the phone

- **Standalone Android:** implement and test the entire Standard/VLM pipeline,
  which needs substantial model assets and likely device resources beyond a typical
  phone; feasibility/performance are not established.
- **Local Windows processing:** run unchanged Windows MinerU 4 on a local PC and
  transfer the resulting DOCX/ZIP to Android. That preserves the real Windows
  pipeline, but it is **not standalone/offline Android inference**.
