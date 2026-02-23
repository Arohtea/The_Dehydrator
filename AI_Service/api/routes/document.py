import uuid
import tempfile
from pathlib import Path

from fastapi import APIRouter, UploadFile, File, HTTPException, Header
from typing import Optional

from services.document_parser import parse_document
from services.chunking import chunk_text
from services.vector_store import store_chunks, delete_by_doc_id

router = APIRouter()

ALLOWED_SUFFIXES = {".pdf", ".docx", ".txt"}


@router.post("/upload")
async def upload_document(
    file: UploadFile = File(...),
    x_api_key: Optional[str] = Header(None),
    x_chunk_size: Optional[int] = Header(None),
    x_chunk_overlap: Optional[int] = Header(None),
):
    suffix = Path(file.filename).suffix.lower()
    if suffix not in ALLOWED_SUFFIXES:
        raise HTTPException(400, f"不支持的文件格式: {suffix}")

    doc_id = str(uuid.uuid4())

    with tempfile.NamedTemporaryFile(delete=False, suffix=suffix) as tmp:
        tmp.write(await file.read())
        tmp_path = tmp.name

    try:
        text = parse_document(tmp_path)
        chunks = chunk_text(text, chunk_size=x_chunk_size, chunk_overlap=x_chunk_overlap)
        store_chunks(chunks, doc_id, api_key=x_api_key)
    finally:
        Path(tmp_path).unlink(missing_ok=True)

    return {
        "doc_id": doc_id,
        "filename": file.filename,
        "chunks": len(chunks),
        "text_length": len(text),
    }


@router.delete("/{doc_id}")
async def delete_document(doc_id: str):
    delete_by_doc_id(doc_id)
    return {"ok": True}
