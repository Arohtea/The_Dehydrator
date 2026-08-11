"""中英文图片 OCR 适配层。"""

from __future__ import annotations

from threading import Lock
from typing import Any


_engine: Any = None
_engine_init_lock = Lock()
_inference_lock = Lock()


def _get_engine():
    global _engine
    if _engine is None:
        with _engine_init_lock:
            if _engine is None:
                from rapidocr import RapidOCR

                _engine = RapidOCR(
                    params={
                        "Det.lang_type": "ch",
                        "Cls.lang_type": "ch",
                        "Rec.lang_type": "ch",
                    }
                )
    return _engine


def ocr_image(image_bytes: bytes) -> str:
    """识别图片中的中英文文字并转换为文本。

    Args:
        image_bytes: 图片二进制内容，格式由图片本身的编码决定。

    Returns:
        按图片阅读顺序排列的 OCR 文本；没有识别到有效文字时返回空字符串。

    Raises:
        Exception: OCR 依赖、模型或推理过程失败时原样抛出，由上传路由转换为
            现有的解析错误。
    """
    if not image_bytes:
        return ""

    with _inference_lock:
        result = _get_engine()(image_bytes)
    if result is None or len(result) == 0:
        return ""
    return (result.to_markdown() or "").strip()
