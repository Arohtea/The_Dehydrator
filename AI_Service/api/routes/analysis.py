from typing import Annotated, Literal

from fastapi import APIRouter, Header, HTTPException, Request
from pydantic import BaseModel, Field

from services.argument_chain import extract_argument_chain
from services.logic_flaw import detect_logic_flaws
from services.cross_validation import cross_validate
from services.model_config import parse_header_model_config
from services.vector_store import get_document_points

router = APIRouter()


class AnalysisRequest(BaseModel):
    doc_id: str = Field(min_length=1, max_length=100)
    mode: Literal["deep", "quick"] = "deep"
    reference_library_ids: list[Annotated[str, Field(min_length=1, max_length=100)]] = Field(
        default_factory=list, max_length=50
    )


def _get_chunks(doc_id: str) -> list[str]:
    points = get_document_points(doc_id, source_type="analysis_document")
    return [
        point.payload["text"]
        for point in points
        if point.payload and point.payload.get("text")
    ]


@router.post("/argument-chain")
async def argument_chain(
        request: Request,
        req: AnalysisRequest,
        x_map_workers: int = Header(...)):
    """提取文档论据链。

    Args:
        request: 包含文本模型配置 Header 的请求。
        req: 分析文档标识。
        x_map_workers: 数据库传入的并发数。

    Returns:
        文档 ID 与论据链结果。
    """
    chunks = _get_chunks(req.doc_id)
    if not chunks:
        raise HTTPException(404, "未找到该文档的内容")
    try:
        text_config = parse_header_model_config(request.headers, "X-Text", "文本模型")
    except ValueError as error:
        raise HTTPException(422, str(error)) from error
    result = extract_argument_chain(
        chunks,
        text_config=text_config,
        map_workers=x_map_workers,
    )
    return {"doc_id": req.doc_id, "argument_chain": result}


@router.post("/logic-flaws")
async def logic_flaws(
        request: Request,
        req: AnalysisRequest,
        x_map_workers: int = Header(...)):
    """提取论据链并检测逻辑漏洞。

    Args:
        request: 包含文本模型配置 Header 的请求。
        req: 分析文档标识。
        x_map_workers: 数据库传入的并发数。

    Returns:
        文档 ID 与逻辑漏洞结果。
    """
    chunks = _get_chunks(req.doc_id)
    if not chunks:
        raise HTTPException(404, "未找到该文档的内容")
    try:
        text_config = parse_header_model_config(request.headers, "X-Text", "文本模型")
    except ValueError as error:
        raise HTTPException(422, str(error)) from error
    chain = extract_argument_chain(
        chunks,
        text_config=text_config,
        map_workers=x_map_workers,
    )
    flaws = detect_logic_flaws(chain, text_config=text_config)
    return {"doc_id": req.doc_id, "logic_flaws": flaws}


@router.post("/cross-validate")
async def cross_validation(
        request: Request,
        req: AnalysisRequest,
        x_map_workers: int = Header(...)):
    """执行交叉验证，深度模式必须由调用方提供 Tavily Key。

    Args:
        request: 包含文本、向量与 Tavily 配置 Header 的请求。
        req: 分析模式、文档与资料集范围。
        x_map_workers: 数据库传入的并发数。

    Returns:
        文档 ID 与交叉验证结果。
    """
    chunks = _get_chunks(req.doc_id)
    if not chunks:
        raise HTTPException(404, "未找到该文档的内容")
    try:
        text_config = parse_header_model_config(request.headers, "X-Text", "文本模型")
        vector_config = parse_header_model_config(request.headers, "X-Embedding", "向量模型")
    except ValueError as error:
        raise HTTPException(422, str(error)) from error
    tavily_api_key = request.headers.get("X-Tavily-Api-Key")
    if req.mode == "deep" and not tavily_api_key:
        raise HTTPException(422, "深度交叉验证需要通过 X-Tavily-Api-Key 提供 Tavily API Key")
    chain = extract_argument_chain(
        chunks,
        text_config=text_config,
        map_workers=x_map_workers,
    )
    results = cross_validate(
        chain,
        text_config=text_config,
        vector_config=vector_config,
        mode=req.mode,
        tavily_api_key=tavily_api_key,
        doc_id=req.doc_id,
        reference_library_ids=req.reference_library_ids,
        map_workers=x_map_workers,
    )
    return {"doc_id": req.doc_id, "cross_validation": results}
