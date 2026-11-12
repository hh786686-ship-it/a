import tempfile
import unittest
from pathlib import Path
from unittest.mock import Mock, patch

from word_bridge import open_docx_in_word

class WordHandoffTests(unittest.TestCase):
    def test_binary_content_untouched(self):
        with tempfile.TemporaryDirectory() as tmp:
            p=Path(tmp)/"中文 page-breaks.docx"
            original=b"original DOCX bytes with formatting, font sizes, page breaks"
            p.write_bytes(original)
            opener=Mock()
            assert open_docx_in_word(p,startfile=opener)==p.resolve()
            opener.assert_called_once_with(str(p.resolve()))
            assert p.read_bytes()==original

    def test_invalid_input(self):
        with tempfile.TemporaryDirectory() as tmp:
            pdf=Path(tmp)/"test.pdf";pdf.write_bytes(b"pdf")
            with self.assertRaises(ValueError):open_docx_in_word(pdf,startfile=Mock())
            with self.assertRaises(FileNotFoundError):open_docx_in_word(Path(tmp)/"absent.docx",startfile=Mock())

    def test_app_routes_docx_without_flattening(self):
        import tkinter as tk
        import pocket_highlighter as ph
        from docx import Document
        with tempfile.TemporaryDirectory() as tmp:
            p=Path(tmp)/"sample.docx"
            doc=Document()
            doc.add_paragraph("Chinese pinyin and characters 你有")
            doc.add_page_break()
            doc.add_paragraph("Next page")
            doc.save(p)
            original=p.read_bytes()
            root=tk.Tk()
            try:
                app=ph.App(root)
                with patch("word_bridge.open_docx_in_word",return_value=p.resolve()) as mock_open:
                    app.load(p)
                    mock_open.assert_called_once_with(p)
                    assert app.last_word_path==p.resolve()
                    assert app.mode!="docx"
                    assert app.path is None
                assert p.read_bytes()==original
                app.close()
            finally:
                try:root.destroy()
                except tk.TclError:pass
