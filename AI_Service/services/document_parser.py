import fitz
from docx import Document
from pathlib import Path


def parse_pdf(file_path: str) -> str:
    doc = fitz.open(file_path)
    return "\n".join(page.get_text() for page in doc)


def parse_docx(file_path: str) -> str:
    doc = Document(file_path)
    return "\n".join(p.text for p in doc.paragraphs if p.text.strip())


def parse_txt(file_path: str) -> str:
    return Path(file_path).read_text(encoding="utf-8")


PARSERS = {
    ".pdf": parse_pdf,
    ".docx": parse_docx,
    ".txt": parse_txt,
}


def parse_document(file_path: str) -> str:
    suffix = Path(file_path).suffix.lower()
    parser = PARSERS.get(suffix)
    if not parser:
        raise ValueError(f"不支持的文件格式: {suffix}")
    return parser(file_path)
