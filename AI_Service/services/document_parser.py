"""PDF、DOCX 和纯文本文档的统一解析入口。"""

from pathlib import Path
from typing import Iterator

import fitz
from docx import Document
from docx.oxml.ns import qn

from services.ocr import ocr_image


OCR_MARKER = "[图片 OCR]"


def _ocr_block(image_bytes: bytes) -> str:
    # OCR 只负责识别图片内容，统一在这里加标记，方便后续模型知道文字来自图片而非原生正文。
    text = ocr_image(image_bytes)
    if not text:
        return ""
    return f"{OCR_MARKER}\n{text}"


def _iter_docx_image_blobs(doc: Document) -> Iterator[bytes]:
    # DOCX 图片不是普通 paragraph 文本，需要沿着底层 XML 关系找到实际图片二进制。
    for blip in doc.element.body.iter(qn("a:blip")):
        # r:embed 是图片关系 ID，缺失时说明该节点不是可读取的嵌入图片。
        relationship_id = blip.get(qn("r:embed"))
        if not relationship_id:
            continue
        # 通过关系表取出图片部件，并过滤掉超链接或其他非 image 类型资源。
        image_part = doc.part.related_parts.get(relationship_id)
        if image_part is None or not image_part.content_type.startswith("image/"):
            continue
        # 只把原始字节交给 OCR，调用方决定如何把识别结果拼入文档文本。
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
    # pages 保留 PDF 的页边界，后续模型看到的文本顺序与用户阅读顺序一致。
    pages = []
    with fitz.open(file_path) as doc:
        for page in doc:
            # 优先读取 PDF 内嵌文字；只有扫描页或页面内图片才需要 OCR。
            native_text = page.get_text()
            page_parts = [native_text] if native_text.strip() else []

            if native_text.strip():
                # 页面已有原生文字时，对每个嵌入图片做一次 OCR，并用 xref 去重。
                seen_xrefs = set()
                for image_info in page.get_images(full=True):
                    xref = image_info[0]
                    if xref <= 0 or xref in seen_xrefs:
                        continue
                    seen_xrefs.add(xref)
                    # PyMuPDF 根据 xref 解出图片 bytes，OCR 不需要知道图片在 PDF 中的布局。
                    image = doc.extract_image(xref)
                    ocr_text = _ocr_block(image["image"])
                    if ocr_text:
                        page_parts.append(ocr_text)
            else:
                # 扫描页直接识别整页，避免对同一张整页图片重复 OCR。
                # 200 DPI 是识别准确率与临时图片大小之间的固定折中。
                pixmap = page.get_pixmap(dpi=200, alpha=False)
                ocr_text = _ocr_block(pixmap.tobytes("png"))
                if ocr_text:
                    page_parts.append(ocr_text)

            # 丢弃空片段后再合并，避免后续切分产生大量空白 chunk。
            pages.append("\n".join(part for part in page_parts if part.strip()))
    return "\n".join(pages)


def parse_docx(file_path: str) -> str:
    """提取 DOCX 段落文本，并补充文档图片中的中英文 OCR 文字。

    Args:
        file_path: 待读取的 DOCX 临时文件路径。

    Returns:
        按文档顺序合并的非空段落文本。
    """
    # python-docx 先读取可直接提取的段落，再单独遍历底层图片资源。
    doc = Document(file_path)
    parts = [p.text for p in doc.paragraphs if p.text.strip()]
    # 图片 OCR 追加到文档文本末尾，保证文字不会被静默丢掉，即使图片布局顺序无法从 XML 完全恢复。
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
    # 统一使用 UTF-8，上传路由已限制文件类型，解码失败会交给路由转换成解析错误。
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
    # 扩展名只用于选择解析器，不把文件名大小写差异当成不同格式。
    suffix = Path(file_path).suffix.lower()
    parser = PARSERS.get(suffix)
    if not parser:
        raise ValueError(f"不支持的文件格式: {suffix}")
    # 具体解析异常向上抛出，由 HTTP 路由统一隐藏底层库细节。
    return parser(file_path)
