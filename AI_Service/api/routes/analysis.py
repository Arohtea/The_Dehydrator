from fastapi import APIRouter
from pydantic import BaseModel

from services.argument_chain import extract_argument_chain
from services.logic_flaw import detect_logic_flaws
from services.cross_validation import cross_validate
from services.vector_store import get_client, ensure_collection
from config.settings import settings

router = APIRouter()


class AnalysisRequest(BaseModel):
    doc_id: str


def _get_chunks(doc_id: str) -> list[str]:
    ensure_collection()
    client = get_client()
    results = client.scroll(
        settings.qdrant_collection,
        scroll_filter={"must": [{"key": "doc_id", "match": {"value": doc_id}}]},
        limit=1000,
    )
    return [p.payload["text"] for p in results[0]]


@router.post("/argument-chain")
async def argument_chain(req: AnalysisRequest):
    chunks = _get_chunks(req.doc_id)
    if not chunks:
        return {"error": "未找到该文档的内容"}
    result = extract_argument_chain(chunks)
    return {"doc_id": req.doc_id, "argument_chain": result}


@router.post("/logic-flaws")
async def logic_flaws(req: AnalysisRequest):
    chunks = _get_chunks(req.doc_id)
    if not chunks:
        return {"error": "未找到该文档的内容"}
    chain = extract_argument_chain(chunks)
    flaws = detect_logic_flaws(chain)
    return {"doc_id": req.doc_id, "logic_flaws": flaws}


@router.post("/cross-validate")
async def cross_validation(req: AnalysisRequest):
    chunks = _get_chunks(req.doc_id)
    if not chunks:
        return {"error": "未找到该文档的内容"}
    chain = extract_argument_chain(chunks)
    results = cross_validate(chain)
    return {"doc_id": req.doc_id, "cross_validation": results}
