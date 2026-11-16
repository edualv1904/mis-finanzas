"""Convierte cada estado de cuenta PDF de estados/ a un .txt al lado, para poder analizarlos."""
import sys
from pathlib import Path

import pdfplumber

for pdf in sorted(Path(sys.argv[1]).rglob("*.pdf")):
    txt = pdf.with_suffix(".txt")
    if txt.exists():
        continue
    with pdfplumber.open(pdf) as doc:
        txt.write_text("\n\f\n".join(p.extract_text() or "" for p in doc.pages), encoding="utf-8")
    print("ok", pdf.relative_to(sys.argv[1]))
