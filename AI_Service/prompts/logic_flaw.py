LOGIC_FLAW_PROMPT = """你是一位批判性思维专家。请基于以下论据链，识别其中的逻辑漏洞和薄弱环节。

论据链：
{argument_chain}

请从以下维度分析：
1. 因果谬误：是否存在虚假因果关系
2. 证据不足：哪些论点缺乏充分证据支撑
3. 过度概括：是否从有限样本推导出普遍结论
4. 循环论证：是否存在前提和结论互相依赖
5. 隐含假设：有哪些未明确说明但必须成立的前提

以JSON格式输出：
{{
  "overall_rigor_score": 1-10的评分,
  "flaws": [
    {{
      "type": "漏洞类型",
      "location": "涉及的论据步骤编号",
      "description": "具体描述",
      "severity": "high/medium/low",
      "suggestion": "改进建议"
    }}
  ],
  "summary": "整体逻辑质量评价"
}}

只输出JSON，不要其他内容。"""
