"""分析相关 HTTP 接口。

路由层只负责校验请求、读取 Qdrant 文档片段并组装模型配置；论据链、漏洞检测
和交叉验证的实际执行分别委托给 `services` 中的业务函数。逻辑漏洞接口只消费
调用方已经生成的论据链，不重复执行论据提取。
"""

from typing import Annotated, Literal

from fastapi import APIRouter, Header, HTTPException, Request
from pydantic import BaseModel, Field

from services.argument_chain import extract_argument_chain
from services.logic_flaw import detect_logic_flaws
from services.cross_validation import cross_validate
from services.model_config import parse_header_model_config
from services.output_models import ArgumentChainResult
from services.vector_store import get_document_points

router = APIRouter()


class AnalysisRequest(BaseModel):
    """分析接口共享的请求模型。

    `mode` 决定是否执行 Tavily 联网搜索，`reference_library_ids` 限定交叉验证
    可使用的参考资料范围；当前请求最多携带 50 个资料库 ID。
    """

    doc_id: str = Field(min_length=1, max_length=100)
    mode: Literal["deep", "quick"] = "deep"
    reference_library_ids: list[Annotated[str, Field(min_length=1, max_length=100)]] = Field(
        default_factory=list, max_length=50
    )


class LogicFlawRequest(BaseModel):
    """基于已生成论据链执行逻辑漏洞检测的请求模型。"""

    doc_id: str = Field(min_length=1, max_length=100)
    argument_chain: ArgumentChainResult


def _get_chunks(doc_id: str) -> list[str]:
    """读取指定分析文档的文本片段并过滤空 payload。

    Args:
        doc_id: Qdrant 中分析文档的业务 ID。

    Returns:
        按 Qdrant 返回顺序排列的非空文本片段。

    Notes:
        通过 `source_type=analysis_document` 限定数据来源，避免把参考资料
        向量误当作当前论文内容参与论据提取。
    """
    # 只读取当前上传的分析文档，不能把参考资料库中的相似片段混进论文论据。
    points = get_document_points(doc_id, source_type="analysis_document")
    # Qdrant payload 可能缺少 text 或为空；过滤后再交给模型，避免生成无意义的输入。
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

    Raises:
        HTTPException: 文档不存在、模型 Header 无效或文档没有可分析内容时返回
            对应的 HTTP 错误。
    """
    # 先从向量库取回原文片段；没有片段时直接返回 404，不浪费模型调用。
    chunks = _get_chunks(req.doc_id)
    if not chunks:
        raise HTTPException(404, "未找到该文档的内容")
    try:
        # 文本模型配置由 Business Service 随本次分析传入，保证任务使用用户当前选择。
        text_config = parse_header_model_config(request.headers, "X-Text", "文本模型")
    except ValueError as error:
        raise HTTPException(422, str(error)) from error
    # 论据链服务内部会执行 MAP 并发提取和 REDUCE 汇总。
    result = extract_argument_chain(
        chunks,
        text_config=text_config,
        map_workers=x_map_workers,
    )
    return {"doc_id": req.doc_id, "argument_chain": result}


@router.post("/logic-flaws")
async def logic_flaws(
        request: Request,
        req: LogicFlawRequest):
    """使用调用方已生成的论据链检测逻辑漏洞。

    Args:
        request: 包含文本模型配置 Header 的请求。
        req: 分析文档标识和已完成结构化的论据链。

    Returns:
        文档 ID 与逻辑漏洞结果。

    Raises:
        HTTPException: 模型 Header 无效时返回对应的 HTTP 错误。
    """
    try:
        # 漏洞检测使用调用方生成论据链时对应的文本模型配置，保证结果可复现。
        text_config = parse_header_model_config(request.headers, "X-Text", "文本模型")
    except ValueError as error:
        raise HTTPException(422, str(error)) from error
    # 请求模型已校验论据链结构；这里直接复用它，避免重新读取文档和调用 MAP/REDUCE。
    flaws = detect_logic_flaws(req.argument_chain.model_dump(mode="json"), text_config=text_config)
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

    Raises:
        HTTPException: 文档不存在、模型配置无效，或深度模式缺少 Tavily API Key
            时返回对应的 HTTP 错误。
    """
    # 交叉验证同样以当前文档为入口，但后续还会读取参考资料并按模式决定是否联网。
    chunks = _get_chunks(req.doc_id)
    if not chunks:
        raise HTTPException(404, "未找到该文档的内容")
    try:
        # 文本模型负责最终判断，向量模型负责参考资料相似度检索，两者配置必须分别解析。
        text_config = parse_header_model_config(request.headers, "X-Text", "文本模型")
        vector_config = parse_header_model_config(request.headers, "X-Embedding", "向量模型")
    except ValueError as error:
        raise HTTPException(422, str(error)) from error
    # Tavily Key 只从当前请求读取，避免把用户的联网凭证落到 AI Service 环境变量中。
    tavily_api_key = request.headers.get("X-Tavily-Api-Key")
    if req.mode == "deep" and not tavily_api_key:
        # 深度模式的业务契约包含联网证据；缺 Key 时明确拒绝，不静默降级成快速模式。
        raise HTTPException(422, "深度交叉验证需要通过 X-Tavily-Api-Key 提供 Tavily API Key")
    # 先提取论据，再由交叉验证服务为每条论据准备本地、参考资料和联网证据。
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
