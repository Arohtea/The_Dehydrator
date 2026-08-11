"""PDF、DOCX 和纯文本文档的统一解析入口。"""

from pathlib import Path
from typing import Iterator

import fitz
from docx import Document
from docx.oxml.ns import qn

from services.ocr import ocr_image


OCR_MARKER = "[图片 OCR]"


def _ocr_block(image_bytes: bytes) -> str:
    text = ocr_image(image_bytes)
    if not text:
        return ""
    return f"{OCR_MARKER}\n{text}"


def _iter_docx_image_blobs(doc: Document) -> Iterator[bytes]:
    for blip in doc.element.body.iter(qn("a:blip")):
        relationship_id = blip.get(qn("r:embed"))
        if not relationship_id:
            continue
        image_part = doc.part.related_parts.get(relationship_id)
        if image_part is None or not image_part.content_type.startswith("image/"):
            continue
        yield image_part.blob


def parse_pdf(file_path: str) -> str:
    """提取 PDF 原生文字，并补充页面图片中的中英文 OCR 文字。

    Args:
        file_path: 待读取的 PDF 临时文件路径。

    Returns:
        按页排列、使用换行分隔的纯文本。

    Raises:
        Exception: 底层 PDF 解析库无法打开或读取文件时原样抛出，交由路由层
            转换为用户可理解的 422 错误。
    """
    pages = []
    with fitz.open(file_path) as doc:
        for page in doc:
            native_text = page.get_text()
            page_parts = [native_text] if native_text.strip() else []

            if native_text.strip():
                seen_xrefs = set()
                for image_info in page.get_images(full=True):
                    xref = image_info[0]
                    if xref <= 0 or xref in seen_xrefs:
                        continue
                    seen_xrefs.add(xref)
                    image = doc.extract_image(xref)
                    ocr_text = _ocr_block(image["image"])
                    if ocr_text:
                        page_parts.append(ocr_text)
            else:
                # 扫描页直接识别整页，避免对同一张整页图片重复 OCR。
                pixmap = page.get_pixmap(dpi=200, alpha=False)
                ocr_text = _ocr_block(pixmap.tobytes("png"))
                if ocr_text:
                    page_parts.append(ocr_text)

            pages.append("\n".join(part for part in page_parts if part.strip()))
    return "\n".join(pages)


def parse_docx(file_path: str) -> str:
    """提取 DOCX 段落文本，并补充文档图片中的中英文 OCR 文字。

    Args:
        file_path: 待读取的 DOCX 临时文件路径。

    Returns:
        按文档顺序合并的非空段落文本。
    """
    doc = Document(file_path)
    parts = [p.text for p in doc.paragraphs if p.text.strip()]
    for image_bytes in _iter_docx_image_blobs(doc):
        ocr_text = _ocr_block(image_bytes)
        if ocr_text:
            parts.append(ocr_text)
    return "\n".join(parts)


def parse_txt(file_path: str) -> str:
    """以 UTF-8 编码读取纯文本文件。

    Args:
        file_path: 待读取的文本文件路径。

    Returns:
        文件完整文本内容。
    """
    return Path(file_path).read_text(encoding="utf-8")


PARSERS = {
    ".pdf": parse_pdf,
    ".docx": parse_docx,
    ".txt": parse_txt,
}


def parse_document(file_path: str) -> str:
    """根据文件后缀选择对应解析器。

    Args:
        file_path: 已保存到本地的待解析文件路径。

    Returns:
        解析后的纯文本。

    Raises:
        ValueError: 文件后缀不在支持列表中。
        Exception: 具体解析器读取失败时向上抛出。
    """
    suffix = Path(file_path).suffix.lower()
    parser = PARSERS.get(suffix)
    if not parser:
        raise ValueError(f"不支持的文件格式: {suffix}")
    return parser(file_path)
