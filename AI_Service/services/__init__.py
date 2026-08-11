"""AI Service 共享的小型文本处理工具。"""

import re


def strip_markdown_json(text: str) -> str:
    """去除 LLM 返回的 Markdown JSON 代码块包裹。

    Args:
        text: 可能被 `````json`````` 包裹的模型输出。

    Returns:
        去掉代码块标记并清理首尾空白后的文本；普通 JSON 文本保持原内容。
    """
    # 模型可能返回带 json 标记的代码围栏；只去掉外围标记，不改动 JSON 内部内容。
    match = re.search(r"```(?:json)?\s*\n?(.*?)```", text, re.DOTALL)
    return match.group(1).strip() if match else text.strip()
