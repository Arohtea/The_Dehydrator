import json
from typing import Literal, TypeVar

from json_repair import repair_json
from pydantic import BaseModel, Field, ValidationError

from services import strip_markdown_json


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
    """清理、修复并校验 LLM 输出，始终返回结构化字典。"""
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
