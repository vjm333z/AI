# -*- coding: utf-8 -*-
"""
bge-reranker-v2-m3 라우터.
sentence-transformers CrossEncoder 사용.

POST /rerank
{
  "query": "질문 텍스트",
  "documents": ["문서1", "문서2", ...],
  "top_k": 3
}
=>
{
  "results": [
    {"index": 2, "score": 0.91},
    {"index": 0, "score": 0.85}
  ]
}
"""

import logging
import math
import os
import threading
from typing import List, Optional

from fastapi import APIRouter, HTTPException
from pydantic import BaseModel

logger = logging.getLogger(__name__)

MODEL_NAME = os.getenv("RERANKER_MODEL", "BAAI/bge-reranker-v2-m3")
MAX_LENGTH = int(os.getenv("RERANKER_MAX_LEN", "512"))

# ── 모델 상태 (lifespan에서 background thread로 로드) ──────────
_model = None
_model_lock = threading.Lock()
_load_error: Optional[str] = None


def load_model() -> None:
    """모델 로드 (블로킹). lifespan에서 별 thread로 호출 권장."""
    global _model, _load_error
    with _model_lock:
        if _model is not None:
            return
        try:
            from sentence_transformers import CrossEncoder
            logger.info("Loading CrossEncoder model: %s (max_length=%d)", MODEL_NAME, MAX_LENGTH)
            _model = CrossEncoder(MODEL_NAME, max_length=MAX_LENGTH)
            logger.info("Reranker model loaded")
        except Exception as e:
            _load_error = f"{type(e).__name__}: {e}"
            logger.exception("Reranker model load failed")


def is_model_ready() -> bool:
    return _model is not None


def get_load_error() -> Optional[str]:
    return _load_error


def _sigmoid(x: float) -> float:
    return 1.0 / (1.0 + math.exp(-x))


# ── API ───────────────────────────────────────────────────────
router = APIRouter(tags=["rerank"])


class RerankRequest(BaseModel):
    query: str
    documents: List[str]
    top_k: int = 3


class RerankItem(BaseModel):
    index: int
    score: float


class RerankResponse(BaseModel):
    results: List[RerankItem]


@router.post("/rerank", response_model=RerankResponse)
def rerank(req: RerankRequest):
    if _model is None:
        if _load_error:
            raise HTTPException(status_code=500, detail=f"Reranker model load failed: {_load_error}")
        raise HTTPException(status_code=503, detail="Reranker model loading — try again shortly")

    if not req.documents:
        return RerankResponse(results=[])

    pairs = [[req.query, doc] for doc in req.documents]
    raw_scores = _model.predict(pairs, show_progress_bar=False)

    normalized = [_sigmoid(float(s)) for s in raw_scores]

    indexed = list(enumerate(normalized))
    indexed.sort(key=lambda x: x[1], reverse=True)
    top = indexed[: req.top_k]

    return RerankResponse(
        results=[RerankItem(index=i, score=s) for i, s in top]
    )
