CROSS_VALIDATION_PROMPT = """你是一位学术事实核查专家。请对以下论据进行交叉验证。

待验证论据：
{claim}

{document_evidence_label}:
{document_evidence}

参考资料检索到的相关内容：
{reference_evidence}

联网搜索到的相关内容：
{web_evidence}

说明：
{mode_note}

重要规则：
1. 快速分析时，只能依据模型自身知识和可选参考资料判断，不得把当前上传论文当作验证证据。
2. 深度分析时，只能综合模型自身知识、可选参考资料和联网搜索结果判断，不得把当前上传论文当作验证证据。
3. `local_evidence_summary` 始终表示“模型知识摘要”。

请从以下维度进行验证：
1. 一致性：各来源是否支持该论据
2. 矛盾点：是否存在与该论据相反的证据
3. 补充信息：是否有重要的补充或修正

以JSON格式输出：
{{
  "claim": "原始论据",
  "verification_status": "supported/partially_supported/contradicted/unverifiable",
  "confidence": 0.0-1.0,
  "local_evidence_summary": "{local_evidence_summary_hint}",
  "reference_evidence_summary": "参考资料证据摘要",
  "web_evidence_summary": "联网搜索证据摘要",
  "contradictions": ["矛盾点列表"],
  "supplements": ["补充信息列表"],
  "conclusion": "综合验证结论"
}}

只输出JSON，不要其他内容。"""
