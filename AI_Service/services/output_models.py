"""LLM 输出的 Pydantic 结构和容错解析。"""

import json
from typing import Literal, TypeVar

from json_repair import repair_json
from pydantic import BaseModel, Field, ValidationError

from services import strip_markdown_json


class ArgumentStep(BaseModel):
    """论据链中的单个论据步骤及其漏洞汇总标记。"""

    # 模型有时返回数字、有时返回“第 N 步”等文本，因此这里保留两种兼容类型。
    step: int | str | None = None
    # 下面三项分别保存论点、支持论据和模型对二者关系的解释，供后续漏洞检测使用。
    claim: str = Field(default="", max_length=10_000)
    evidence: str = Field(default="", max_length=10_000)
    reasoning: str = Field(default="", max_length=10_000)
    relation_to_next: str = Field(default="", max_length=1_000)
    # 这些字段是服务端回标给前端的汇总标记，不要求模型每次都返回完整值。
    logic_flaw: bool = False
    logic_flaw_count: int = Field(default=0, ge=0, le=100)
    logic_flaw_severity: Literal["high", "medium", "low"] | None = None


class ArgumentChainResult(BaseModel):
    """论据链 Map/Reduce 阶段的最终结构。"""

    # REDUCE 阶段输出文档主题、总论点及按原文关系排列的步骤列表。
    title: str = Field(default="", max_length=1_000)
    main_conclusion: str = Field(default="", max_length=10_000)
    argument_chain: list[ArgumentStep] = Field(default_factory=list, max_length=1_000)


class LogicFlaw(BaseModel):
    """单个逻辑漏洞及其定位、严重程度和改进建议。"""

    # 每个漏洞包含可展示的类型、位置、描述和改进建议；step_numbers 用于精确回标。
    type: str = Field(default="", max_length=1_000)
    location: str = Field(default="", max_length=1_000)
    step_numbers: list[int] = Field(default_factory=list, max_length=100)
    description: str = Field(default="", max_length=10_000)
    severity: Literal["high", "medium", "low"] = "low"
    suggestion: str = Field(default="", max_length=10_000)


class LogicFlawResult(BaseModel):
    """逻辑漏洞检测的整体评分、漏洞列表和总结。"""

    # 总体评分允许为空，因为模型可能只返回漏洞清单而不提供可靠分数。
    overall_rigor_score: float | None = Field(default=None, ge=0, le=10)
    flaws: list[LogicFlaw] = Field(default_factory=list, max_length=1_000)
    summary: str = Field(default="", max_length=10_000)


class WebSource(BaseModel):
    """可展示给用户的联网来源标题和 URL。"""

    # 只保留前端需要展示和点击的字段，联网正文不会随结果模型重复返回。
    title: str = Field(default="", max_length=2_000)
    url: str = Field(default="", max_length=4_000)


class CrossValidationResult(BaseModel):
    """单条论据的交叉验证结果及多来源证据摘要。"""

    # 验证结果同时保留本地知识、参考资料和网页证据摘要，便于用户追溯结论来源。
    claim: str = Field(default="", max_length=10_000)
    verification_status: Literal[
        "supported", "partially_supported", "contradicted", "unverifiable"
    ] = "unverifiable"
    confidence: float = Field(default=0, ge=0, le=1)
    local_evidence_summary: str = Field(default="", max_length=10_000)
    reference_evidence_summary: str = Field(default="", max_length=10_000)
    web_evidence_summary: str = Field(default="", max_length=10_000)
    web_sources: list[WebSource] = Field(default_factory=list, max_length=5)
    contradictions: list[str] = Field(default_factory=list, max_length=100)
    supplements: list[str] = Field(default_factory=list, max_length=100)
    conclusion: str = Field(default="", max_length=10_000)


class ReferenceClassificationResult(BaseModel):
    """参考资料自动归档的文件夹、分类和置信度建议。"""

    # None 表示模型没有足够把握推荐位置，归档主流程仍可使用默认位置完成。
    folder_name: str | None = Field(default=None, max_length=255)
    category_name: str | None = Field(default=None, max_length=255)
    confidence: float = Field(default=0, ge=0, le=1)
    reason: str = Field(default="", max_length=2_000)


ModelT = TypeVar("ModelT", bound=BaseModel)


def parse_and_validate(model: type[ModelT], text: str) -> dict:
    """清理、修复并校验 LLM 输出，始终返回结构化字典。

    Args:
        model: 目标 Pydantic 模型类型。
        text: LLM 返回的原始文本，可能包含 Markdown 代码块或轻微 JSON 错误。

    Returns:
        通过目标模型校验后的 JSON 兼容字典。

    Raises:
        ValueError: 输出超过长度上限、无法修复为 JSON，或不符合目标模型结构。

    Notes:
        先尝试标准 JSON 解析，失败后才使用 `json_repair`；修复结果仍必须经过
        Pydantic 校验，避免把格式修复误当成业务字段有效。
    """
    # 先限制原始输出长度，避免异常模型响应在 JSON 修复前消耗过多内存和 CPU。
    if len(text) > 200_000:
        raise ValueError("模型输出超过系统限制")

    # 去掉 ```json 代码围栏等展示格式，留下真正交给 JSON 解析器的内容。
    cleaned = strip_markdown_json(text)
    try:
        try:
            # 正常模型输出优先走标准 JSON，速度快且不会主动改变原始字段。
            value = json.loads(cleaned)
        except (json.JSONDecodeError, TypeError, ValueError):
            # 仅在标准解析失败时启用容错修复，兼容尾逗号、未加引号等轻微格式问题。
            value = repair_json(cleaned, return_objects=True)
        # 修复 JSON 只解决语法问题，最终仍由目标模型校验字段类型、范围和嵌套结构。
        return model.model_validate(value).model_dump(mode="json")
    except ValidationError as error:
        # 将底层校验细节收敛为稳定的业务错误，避免把内部字段路径直接暴露给客户端。
        raise ValueError(f"模型输出不符合 {model.__name__} 结构") from error
    except Exception as error:
        # JSON 解析或修复失败时返回统一错误，调用方可选择整条失败或降级为不可验证。
        raise ValueError("模型输出无法解析为结构化 JSON") from error
