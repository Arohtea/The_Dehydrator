"""PDF、DOCX 和纯文本文档的统一解析入口。"""

import fitz
from docx import Document
from pathlib import Path


def parse_pdf(file_path: str) -> str:
    """提取 PDF 每一页的文本并按页合并。

    Args:
        file_path: 待读取的 PDF 临时文件路径。

    Returns:
        按页排列、使用换行分隔的纯文本。

    Raises:
        Exception: 底层 PDF 解析库无法打开或读取文件时原样抛出，交由路由层
            转换为用户可理解的 422 错误。
    """
    with fitz.open(file_path) as doc:
        return "\n".join(page.get_text() for page in doc)


def parse_docx(file_path: str) -> str:
    """提取 DOCX 段落文本并丢弃空段落。

    Args:
        file_path: 待读取的 DOCX 临时文件路径。

    Returns:
        按文档顺序合并的非空段落文本。
    """
    doc = Document(file_path)
    return "\n".join(p.text for p in doc.paragraphs if p.text.strip())


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
