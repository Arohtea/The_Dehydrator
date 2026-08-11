"""LLM 输出的 Pydantic 结构和容错解析。"""

import json
from typing import Literal, TypeVar

from json_repair import repair_json
from pydantic import BaseModel, Field, ValidationError

from services import strip_markdown_json


class ArgumentStep(BaseModel):
    """论据链中的单个论据步骤及其漏洞汇总标记。"""

    step: int | str | None = None
    claim: str = Field(default="", max_length=10_000)
    evidence: str = Field(default="", max_length=10_000)
    reasoning: str = Field(default="", max_length=10_000)
    relation_to_next: str = Field(default="", max_length=1_000)
    logic_flaw: bool = False
    logic_flaw_count: int = Field(default=0, ge=0, le=100)
    logic_flaw_severity: Literal["high", "medium", "low"] | None = None


class ArgumentChainResult(BaseModel):
    """论据链 Map/Reduce 阶段的最终结构。"""

    title: str = Field(default="", max_length=1_000)
    main_conclusion: str = Field(default="", max_length=10_000)
    argument_chain: list[ArgumentStep] = Field(default_factory=list, max_length=1_000)


class LogicFlaw(BaseModel):
    """单个逻辑漏洞及其定位、严重程度和改进建议。"""

    type: str = Field(default="", max_length=1_000)
    location: str = Field(default="", max_length=1_000)
    step_numbers: list[int] = Field(default_factory=list, max_length=100)
    description: str = Field(default="", max_length=10_000)
    severity: Literal["high", "medium", "low"] = "low"
    suggestion: str = Field(default="", max_length=10_000)


class LogicFlawResult(BaseModel):
    """逻辑漏洞检测的整体评分、漏洞列表和总结。"""

    overall_rigor_score: float | None = Field(default=None, ge=0, le=10)
    flaws: list[LogicFlaw] = Field(default_factory=list, max_length=1_000)
    summary: str = Field(default="", max_length=10_000)


class WebSource(BaseModel):
    """可展示给用户的联网来源标题和 URL。"""

    title: str = Field(default="", max_length=2_000)
    url: str = Field(default="", max_length=4_000)


class CrossValidationResult(BaseModel):
    """单条论据的交叉验证结果及多来源证据摘要。"""

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
    if len(text) > 200_000:
        raise ValueError("模型输出超过系统限制")

    cleaned = strip_markdown_json(text)
    try:
        try:
            value = json.loads(cleaned)
        except (json.JSONDecodeError, TypeError, ValueError):
            value = repair_json(cleaned, return_objects=True)
        return model.model_validate(value).model_dump(mode="json")
    except ValidationError as error:
        raise ValueError(f"模型输出不符合 {model.__name__} 结构") from error
    except Exception as error:
        raise ValueError("模型输出无法解析为结构化 JSON") from error
