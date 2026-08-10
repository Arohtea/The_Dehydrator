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

ALLOWED_SUFFIXES = {".pdf", ".docx", ".txt"}


class ArchiveReferenceRequest(BaseModel):
    libraryId: str = Field(min_length=1, max_length=100)
    filename: str = Field(min_length=1, max_length=255)
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
    """
    filename = file.filename or "upload"
    if len(filename) > 255:
        raise HTTPException(422, "文件名长度不能超过 255 个字符")
    suffix = Path(filename).suffix.lower()
    if suffix not in ALLOWED_SUFFIXES:
        raise HTTPException(400, f"不支持的文件格式: {suffix}")

    content_length = request.headers.get("content-length")
    if content_length:
        try:
            if int(content_length) > settings.ai_max_request_bytes:
                raise HTTPException(413, "请求体超过配置上限")
        except ValueError as exc:
            raise HTTPException(400, "Content-Length 无效") from exc

    source_type = x_source_type or "analysis_document"
    if source_type not in {"analysis_document", "reference_document"}:
        raise HTTPException(422, "source_type 不受支持")
    if source_type == "reference_document" and not x_library_id:
        raise HTTPException(400, "参考资料上传缺少 X-Library-Id")

    try:
        vector_config = parse_header_model_config(request.headers, "X-Embedding", "向量模型")
    except ValueError as error:
        raise HTTPException(422, str(error)) from error

    chunk_size = x_chunk_size
    chunk_overlap = x_chunk_overlap
    if not 500 <= chunk_size <= 8000:
        raise HTTPException(422, "chunk_size 必须在 500 到 8000 之间")
    if not 0 <= chunk_overlap < chunk_size:
        raise HTTPException(422, "chunk_overlap 必须满足 0 <= overlap < chunk_size")

    doc_id = str(uuid.uuid4())

    tmp_path = None

    try:
        with tempfile.NamedTemporaryFile(delete=False, suffix=suffix) as tmp:
            tmp_path = tmp.name
            total_bytes = 0
            while chunk := await file.read(1024 * 1024):
                total_bytes += len(chunk)
                if total_bytes > settings.ai_max_upload_bytes:
                    raise HTTPException(413, "文件超过配置上限")
                tmp.write(chunk)
        try:
            text = parse_document(tmp_path)
        except Exception as exc:
            raise HTTPException(422, "文件内容无法解析或格式与扩展名不匹配") from exc
        if len(text) > settings.ai_max_text_chars:
            raise HTTPException(413, "解析后的文本超过系统上限")
        chunks = chunk_text(text, chunk_size=chunk_size, chunk_overlap=chunk_overlap)
        if not chunks:
            raise HTTPException(422, "文档未解析出可用内容")
        if len(chunks) > settings.ai_max_chunks_per_document:
            raise HTTPException(413, "文档片段数量超过系统上限")
        store_chunks(
            chunks,
            doc_id,
            vector_config=vector_config,
            source_type=source_type,
            library_id=x_library_id,
        )
    finally:
        if tmp_path:
            Path(tmp_path).unlink(missing_ok=True)

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
    """
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
    """
    try:
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
        status_code = 404 if "未找到" in str(error) else 422
        raise HTTPException(status_code, str(error)) from error
