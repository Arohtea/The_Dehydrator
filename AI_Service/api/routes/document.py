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
@limiter.limit("10/minute")
async def upload_document(
    request: Request,
    file: UploadFile = File(...),
    x_api_key: Optional[str] = Header(None),
    x_chunk_size: Optional[int] = Header(None),
    x_chunk_overlap: Optional[int] = Header(None),
    x_source_type: Optional[str] = Header(None),
    x_library_id: Optional[str] = Header(None),
):
    filename = file.filename or "upload"
    if len(filename) > 255:
        raise HTTPException(422, "文件名长度不能超过 255 个字符")
    suffix = Path(filename).suffix.lower()
    if suffix not in ALLOWED_SUFFIXES:
        raise HTTPException(400, f"不支持的文件格式: {suffix}")

    content_length = request.headers.get("content-length")
    if content_length:
        try:
            if int(content_length) > settings.max_request_bytes:
                raise HTTPException(413, "请求体超过 25 MiB 限制")
        except ValueError as exc:
            raise HTTPException(400, "Content-Length 无效") from exc

    source_type = x_source_type or "analysis_document"
    if source_type not in {"analysis_document", "reference_document"}:
        raise HTTPException(422, "source_type 不受支持")
    if source_type == "reference_document" and not x_library_id:
        raise HTTPException(400, "参考资料上传缺少 X-Library-Id")

    chunk_size = x_chunk_size if x_chunk_size is not None else settings.chunk_size
    chunk_overlap = x_chunk_overlap if x_chunk_overlap is not None else settings.chunk_overlap
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
                if total_bytes > settings.max_upload_bytes:
                    raise HTTPException(413, "文件超过 20 MiB 限制")
                tmp.write(chunk)
        try:
            text = parse_document(tmp_path)
        except Exception as exc:
            raise HTTPException(422, "文件内容无法解析或格式与扩展名不匹配") from exc
        if len(text) > settings.max_text_chars:
            raise HTTPException(413, "解析后的文本超过系统上限")
        chunks = chunk_text(text, chunk_size=chunk_size, chunk_overlap=chunk_overlap)
        if not chunks:
            raise HTTPException(422, "文档未解析出可用内容")
        if len(chunks) > settings.max_chunks_per_document:
            raise HTTPException(413, "文档片段数量超过系统上限")
        store_chunks(
            chunks,
            doc_id,
            api_key=x_api_key,
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
@limiter.limit("30/minute")
async def delete_document(request: Request, doc_id: str):
    delete_by_doc_id(doc_id)
    return {"ok": True}


@router.post("/{doc_id}/archive-reference")
@limiter.limit("30/minute")
async def archive_reference(
    request: Request,
    doc_id: str,
    req: ArchiveReferenceRequest,
    x_api_key: Optional[str] = Header(None),
    x_model: Optional[str] = Header(None),
):
    try:
        return archive_reference_document(
            doc_id=doc_id,
            library_id=req.libraryId,
            filename=req.filename,
            folder_candidates=req.folderCandidates,
            category_candidates=req.categoryCandidates,
            api_key=x_api_key,
            model=x_model,
        )
    except ValueError as e:
        raise HTTPException(404, str(e))
