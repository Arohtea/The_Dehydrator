import re


def strip_markdown_json(text: str) -> str:
    """去除 LLM 返回的 Markdown JSON 代码块包裹。"""
    match = re.search(r"```(?:json)?\s*\n?(.*?)```", text, re.DOTALL)
    return match.group(1).strip() if match else text.strip()
