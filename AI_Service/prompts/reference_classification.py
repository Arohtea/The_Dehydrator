REFERENCE_CLASSIFICATION_PROMPT = """你是一位资料归档助手。请根据文件名和文档片段，为资料选择最合适的文件夹和分类。

文件名：
{filename}

文档片段：
{document_preview}

现有文件夹候选：
{folder_candidates}

现有分类候选：
{category_candidates}

规则：
1. 优先复用现有文件夹和分类；只有明显不合适时才创建新名称。
2. 文件夹名和分类名都要简短、可复用，避免句子式描述。
3. 如果信息不足，可以降低 confidence，并返回更保守的名称。
4. 只返回一个文件夹和一个分类。

以 JSON 输出：
{{
  "folder_name": "推荐文件夹名称",
  "category_name": "推荐分类名称",
  "confidence": 0.0-1.0,
  "reason": "一句话说明判断依据"
}}

只输出 JSON，不要其他内容。"""
