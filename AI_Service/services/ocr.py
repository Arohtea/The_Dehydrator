"""中英文图片 OCR 适配层。"""

from __future__ import annotations

from threading import Lock
from typing import Any


# OCR 引擎和模型较重，整个进程只初始化一次；锁避免并发首请求重复加载模型。
_engine: Any = None
_engine_init_lock = Lock()
# RapidOCR 推理阶段共享模型状态，串行化可避免线程安全问题。
_inference_lock = Lock()


def _get_engine():
    global _engine
    if _engine is None:
        with _engine_init_lock:
            if _engine is None:
                # 延迟导入和初始化，让没有图片上传的进程启动不必立即加载 OCR 依赖。
                from rapidocr import RapidOCR

                # 明确使用中英文识别能力，适配资料库中常见的中文论文和英文图表。
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
    # 空图片没有可识别内容，直接返回空字符串，调用方可继续处理其他页面文本。
    if not image_bytes:
        return ""

    # 同一个 OCR 引擎实例串行推理，避免底层模型在多线程上传时发生状态竞争。
    with _inference_lock:
        result = _get_engine()(image_bytes)
    if result is None or len(result) == 0:
        # RapidOCR 未识别到文本时可能返回 None 或空结果，统一转换为可拼接的空文本。
        return ""
    # 转成 Markdown 让表格、换行等版式信息尽可能保留给后续文档解析流程。
    return (result.to_markdown() or "").strip()
