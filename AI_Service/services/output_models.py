from typing import Literal, TypeVar

from pydantic import BaseModel, Field, ValidationError


class ArgumentStep(BaseModel):
    step: int | str | None = None
    claim: str = Field(default="", max_length=10_000)
    evidence: str = Field(default="", max_length=10_000)
    reasoning: str = Field(default="", max_length=10_000)
    relation_to_next: str = Field(default="", max_length=1_000)


class ArgumentChainResult(BaseModel):
    title: str = Field(default="", max_length=1_000)
    main_conclusion: str = Field(default="", max_length=10_000)
    argument_chain: list[ArgumentStep] = Field(default_factory=list, max_length=1_000)


class LogicFlaw(BaseModel):
    type: str = Field(default="", max_length=1_000)
    location: str = Field(default="", max_length=1_000)
    description: str = Field(default="", max_length=10_000)
    severity: Literal["high", "medium", "low"] = "low"
    suggestion: str = Field(default="", max_length=10_000)


class LogicFlawResult(BaseModel):
    overall_rigor_score: float | None = Field(default=None, ge=0, le=10)
    flaws: list[LogicFlaw] = Field(default_factory=list, max_length=1_000)
    summary: str = Field(default="", max_length=10_000)


class CrossValidationResult(BaseModel):
    claim: str = Field(default="", max_length=10_000)
    verification_status: Literal[
        "supported", "partially_supported", "contradicted", "unverifiable"
    ] = "unverifiable"
    confidence: float = Field(default=0, ge=0, le=1)
    local_evidence_summary: str = Field(default="", max_length=10_000)
    reference_evidence_summary: str = Field(default="", max_length=10_000)
    web_evidence_summary: str = Field(default="", max_length=10_000)
    contradictions: list[str] = Field(default_factory=list, max_length=100)
    supplements: list[str] = Field(default_factory=list, max_length=100)
    conclusion: str = Field(default="", max_length=10_000)


class ReferenceClassificationResult(BaseModel):
    folder_name: str | None = Field(default=None, max_length=255)
    category_name: str | None = Field(default=None, max_length=255)
    confidence: float = Field(default=0, ge=0, le=1)
    reason: str = Field(default="", max_length=2_000)


ModelT = TypeVar("ModelT", bound=BaseModel)


def parse_and_validate(model: type[ModelT], text: str) -> dict:
    """限制 LLM 输出大小并用结构化模型过滤异常字段和枚举值。"""
    if len(text) > 200_000:
        return {"raw": "模型输出超过系统限制"}
    try:
        import json

        value = json.loads(text)
        return model.model_validate(value).model_dump(mode="json")
    except (ValueError, TypeError, ValidationError):
        return {"raw": "模型输出格式无效"}
