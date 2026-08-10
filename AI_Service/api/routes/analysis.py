from typing import Annotated, Literal

from fastapi import APIRouter, HTTPException
from pydantic import BaseModel, Field

from services.argument_chain import extract_argument_chain
from services.logic_flaw import detect_logic_flaws
from services.cross_validation import cross_validate
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
async def argument_chain(req: AnalysisRequest):
    chunks = _get_chunks(req.doc_id)
    if not chunks:
        raise HTTPException(404, "未找到该文档的内容")
    result = extract_argument_chain(chunks)
    return {"doc_id": req.doc_id, "argument_chain": result}


@router.post("/logic-flaws")
async def logic_flaws(req: AnalysisRequest):
    chunks = _get_chunks(req.doc_id)
    if not chunks:
        raise HTTPException(404, "未找到该文档的内容")
    chain = extract_argument_chain(chunks)
    flaws = detect_logic_flaws(chain)
    return {"doc_id": req.doc_id, "logic_flaws": flaws}


@router.post("/cross-validate")
async def cross_validation(req: AnalysisRequest):
    chunks = _get_chunks(req.doc_id)
    if not chunks:
        raise HTTPException(404, "未找到该文档的内容")
    chain = extract_argument_chain(chunks)
    results = cross_validate(
        chain,
        mode=req.mode,
        doc_id=req.doc_id,
        reference_library_ids=req.reference_library_ids,
    )
    return {"doc_id": req.doc_id, "cross_validation": results}
