"""文档上传、删除和分析文档归档接口。"""

import uuid
import tempfile
from pathlib import Path

from fastapi import APIRouter, File, Header, HTTPException, Request, UploadFile
from typing import Annotated, Optional
from pydantic import BaseModel, Field

from config.settings import settings
from services.document_parser import parse_document
from services.chunking import chunk_text
from services.vector_store import store_chunks, delete_by_doc_id
from services.reference_archive import archive_reference_document
from services.model_config import parse_header_model_config
from api.limiter import limiter

router = APIRouter()

# 只允许解析器已经支持的文档类型，避免把任意文件交给后续解析流程。
ALLOWED_SUFFIXES = {".pdf", ".docx", ".txt"}


class ArchiveReferenceRequest(BaseModel):
    """分析文档归档到参考资料库时的候选位置请求。"""

    # 目标资料库由 Business Service 传入，AI Service 只负责复制向量和生成建议。
    libraryId: str = Field(min_length=1, max_length=100)
    # 文件名用于让分类模型判断主题，也会作为归档结果展示给用户。
    filename: str = Field(min_length=1, max_length=255)
    # 候选项限制在当前资料库已有名称内，避免模型凭空创建目录或分类。
    folderCandidates: list[Annotated[str, Field(min_length=1, max_length=255)]] = Field(
        default_factory=list, max_length=50
    )
    categoryCandidates: list[Annotated[str, Field(min_length=1, max_length=255)]] = Field(
        default_factory=list, max_length=50
    )


@router.post("/upload")
@limiter.limit(settings.ai_upload_rate_limit)
async def upload_document(
    request: Request,
    file: UploadFile = File(...),
    x_chunk_size: int = Header(...),
    x_chunk_overlap: int = Header(...),
    x_source_type: Optional[str] = Header(None),
    x_library_id: Optional[str] = Header(None),
):
    """解析并向量化上传文档。

    Args:
        request: 包含向量模型配置的内部请求。
        file: 待解析的文档。
        x_chunk_size: 数据库传入的分块大小。
        x_chunk_overlap: 数据库传入的分块重叠大小。
        x_source_type: 文档来源类型。
        x_library_id: 参考资料集 ID。

    Returns:
        文档 ID、文件名和解析统计。

    Raises:
        HTTPException: 文件格式、大小、分块参数、模型配置或解析结果不符合系统
            约束时返回对应的 HTTP 错误。

    Notes:
        上传内容先写入临时文件再解析，函数退出时无论成功或失败都会删除临时
        文件；向量写入使用本次请求显式传入的模型配置，避免读取其他任务配置。
    """
    filename = file.filename or "upload"
    # 文件名来自客户端，先限制长度再用于后续响应，避免异常长输入污染日志或界面。
    if len(filename) > 255:
        raise HTTPException(422, "文件名长度不能超过 255 个字符")
    # 解析器按扩展名选择 PDF、DOCX 或纯文本流程，因此扩展名必须在白名单内。
    suffix = Path(filename).suffix.lower()
    if suffix not in ALLOWED_SUFFIXES:
        raise HTTPException(400, f"不支持的文件格式: {suffix}")

    # Content-Length 是请求级的快速拦截；真正读取文件时仍会再次累计大小，防止客户端省略该 Header。
    content_length = request.headers.get("content-length")
    if content_length:
        try:
            if int(content_length) > settings.ai_max_request_bytes:
                raise HTTPException(413, "请求体超过配置上限")
        except ValueError as exc:
            raise HTTPException(400, "Content-Length 无效") from exc

    # 未指定来源时默认为分析文档；参考资料必须同时带资料库 ID，确保向量有明确归属。
    source_type = x_source_type or "analysis_document"
    if source_type not in {"analysis_document", "reference_document"}:
        raise HTTPException(422, "source_type 不受支持")
    if source_type == "reference_document" and not x_library_id:
        raise HTTPException(400, "参考资料上传缺少 X-Library-Id")

    try:
        # 模型配置由调用方按请求传入，上传任务不会误用另一份全局模型设置。
        vector_config = parse_header_model_config(request.headers, "X-Embedding", "向量模型")
    except ValueError as error:
        raise HTTPException(422, str(error)) from error

    # 这里提前校验分块参数，避免已经写入临时文件后才发现任务无法处理。
    chunk_size = x_chunk_size
    chunk_overlap = x_chunk_overlap
    if not 500 <= chunk_size <= 8000:
        raise HTTPException(422, "chunk_size 必须在 500 到 8000 之间")
    if not 0 <= chunk_overlap < chunk_size:
        raise HTTPException(422, "chunk_overlap 必须满足 0 <= overlap < chunk_size")

    # 先生成贯穿“解析、切块、写入向量库”全过程的业务 ID，所有向量用它关联同一文档。
    doc_id = str(uuid.uuid4())

    # 临时文件需要在 finally 中删除；初始化为 None 便于处理文件尚未创建就失败的情况。
    tmp_path = None

    try:
        with tempfile.NamedTemporaryFile(delete=False, suffix=suffix) as tmp:
            tmp_path = tmp.name
            total_bytes = 0
            # 分块读取上传流，避免把整个文件一次性放入内存；读取过程中同步执行大小上限检查。
            while chunk := await file.read(1024 * 1024):
                total_bytes += len(chunk)
                if total_bytes > settings.ai_max_upload_bytes:
                    raise HTTPException(413, "文件超过配置上限")
                tmp.write(chunk)
        try:
            # 解析阶段统一产出纯文本，具体 PDF、DOCX 和 TXT 差异由 document_parser 屏蔽。
            text = parse_document(tmp_path)
        except Exception as exc:
            raise HTTPException(422, "文件内容无法解析或格式与扩展名不匹配") from exc
        # 文本长度限制保护切块、嵌入和 Qdrant 写入的资源消耗。
        if len(text) > settings.ai_max_text_chars:
            raise HTTPException(413, "解析后的文本超过系统上限")
        # 切块保留相邻片段的上下文重叠，后续向量检索以这些片段为最小返回单位。
        chunks = chunk_text(text, chunk_size=chunk_size, chunk_overlap=chunk_overlap)
        if not chunks:
            raise HTTPException(422, "文档未解析出可用内容")
        # 片段数量上限避免超长文档放大嵌入调用和向量存储成本。
        if len(chunks) > settings.ai_max_chunks_per_document:
            raise HTTPException(413, "文档片段数量超过系统上限")
        # 只有解析和切块均成功后才写入 Qdrant，避免留下无法对应原文的半成品向量。
        store_chunks(
            chunks,
            doc_id,
            vector_config=vector_config,
            source_type=source_type,
            library_id=x_library_id,
        )
    finally:
        if tmp_path:
            # 无论解析、向量化还是参数校验在哪一步失败，都不能把上传内容长期留在本机磁盘。
            Path(tmp_path).unlink(missing_ok=True)

    # 返回数据库需要保存的最小索引信息，正文和片段本身由 Qdrant 负责保存。
    return {
        "doc_id": doc_id,
        "filename": filename,
        "chunks": len(chunks),
        "text_length": len(text),
    }


@router.delete("/{doc_id}")
@limiter.limit(settings.ai_delete_rate_limit)
async def delete_document(request: Request, doc_id: str):
    """删除指定文档的全部向量。

    Args:
        request: 用于限流识别的内部请求。
        doc_id: AI Service 文档 ID。

    Returns:
        删除完成状态。

    Notes:
        删除范围由 Qdrant 的 `doc_id` payload 决定，不依赖 PostgreSQL 中的文档
        记录，因此该接口也可用于清理异步向量化失败后留下的向量。
    """
    # 按 doc_id 删除全部片段，覆盖同一文档产生的所有向量而不是只删某一个 chunk。
    delete_by_doc_id(doc_id)
    return {"ok": True}


@router.post("/{doc_id}/archive-reference")
@limiter.limit(settings.ai_archive_rate_limit)
async def archive_reference(
    request: Request,
    doc_id: str,
    req: ArchiveReferenceRequest,
):
    """克隆分析文档向量并生成参考资料分类建议。

    Args:
        request: 包含文本模型配置的内部请求。
        doc_id: AI Service 文档 ID。
        req: 目标资料集与分类候选项。

    Returns:
        新参考文档 ID 与分类建议。

    Raises:
        HTTPException: 文本模型配置无效、源文档向量不存在或归档过程失败时返回
            404 或 422。
    """
    try:
        # 归档只需要文本模型来判断目录和分类；向量复制使用源文档已有的向量，不重新嵌入。
        text_config = parse_header_model_config(request.headers, "X-Text", "文本模型")
        return archive_reference_document(
            doc_id=doc_id,
            library_id=req.libraryId,
            filename=req.filename,
            folder_candidates=req.folderCandidates,
            category_candidates=req.categoryCandidates,
            text_config=text_config,
        )
    except ValueError as error:
        # 源向量不存在属于资源找不到，其余配置或模型输出问题都属于请求参数错误。
        status_code = 404 if "未找到" in str(error) else 422
        raise HTTPException(status_code, str(error)) from error
