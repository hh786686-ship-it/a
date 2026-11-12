"""DOCX handoff that leaves Word formatting and page breaks completely unchanged."""
from __future__ import annotations
import os
from pathlib import Path
import sys
from tkinter import messagebox, ttk


def open_docx_in_word(path, startfile=None):
    p = Path(path).resolve()
    if p.suffix.lower() != ".docx":
        raise ValueError("Only DOCX files are supported by the Word editor")
    if not p.is_file():
        raise FileNotFoundError(p)
    if startfile is None:
        if sys.platform != "win32" or not hasattr(os, "startfile"):
            raise OSError("Microsoft Word handoff requires Windows")
        startfile = os.startfile
    startfile(str(p))
    return p


def install_word_bridge(App):
    previous_load = App.load
    previous_build_ui = App._build_ui

    def build_ui(self):
        previous_build_ui(self)
        ttk.Button(self.root, text="Open DOCX in Word (preserves pages and formatting)",
                   command=self.open_word_docx).pack(before=self.main, anchor="w",
                                                      padx=10, pady=(0, 5))
        self.last_word_path = None

    def open_word_docx(self):
        from tkinter import filedialog
        filename = filedialog.askopenfilename(title="Open DOCX in Microsoft Word",
                                               filetypes=[("Word documents", "*.docx")])
        if filename:
            self.load(Path(filename))

    def load(self, path):
        p = Path(path)
        if p.suffix.lower() != ".docx":
            return previous_load(self, p)
        try:
            resolved = open_docx_in_word(p)
        except Exception as exc:
            messagebox.showerror("Cannot open DOCX in Word",
                f"{exc}\n\nInstall Microsoft Word and set it as the default app for .docx files.")
            return
        self.last_word_path = resolved
        self.status.set("DOCX opened in Word: Ctrl+F finds text, Ctrl+S saves changes. Page breaks and original layout are preserved.")

    App._build_ui = build_ui
    App.open_word_docx = open_word_docx
    App.load = load
