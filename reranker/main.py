"""
bge-reranker-v2-m3 FastAPI 서비스.
sentence-transformers CrossEncoder 사용 (FlagEmbedding 대비 안정적).

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
    {"index": 0, "score": 0.85},
    ...
  ]
}
"""
import logging
import math
import os
from typing import List

from fastapi import FastAPI
from pydantic import BaseModel
from sentence_transformers import CrossEncoder

logging.basicConfig(level=logging.INFO)
logger = logging.getLogger(__name__)

MODEL_NAME = os.getenv("RERANKER_MODEL", "BAAI/bge-reranker-v2-m3")
MAX_LENGTH = int(os.getenv("RERANKER_MAX_LEN", "512"))

logger.info("Loading CrossEncoder model: %s (max_length=%d)", MODEL_NAME, MAX_LENGTH)
model = CrossEncoder(MODEL_NAME, max_length=MAX_LENGTH)
logger.info("Model loaded")

app = FastAPI(title="RAG Reranker", version="1.1.0")


class RerankRequest(BaseModel):
    query: str
    documents: List[str]
    top_k: int = 3


class RerankItem(BaseModel):
    index: int
    score: float


class RerankResponse(BaseModel):
    results: List[RerankItem]


def sigmoid(x: float) -> float:
    # CrossEncoder raw score를 0~1로 정규화 (bge-reranker logit 가정)
    return 1.0 / (1.0 + math.exp(-x))


@app.get("/health")
def health():
    return {"status": "ok", "model": MODEL_NAME}


@app.post("/rerank", response_model=RerankResponse)
def rerank(req: RerankRequest):
    if not req.documents:
        return RerankResponse(results=[])

    pairs = [[req.query, doc] for doc in req.documents]
    raw_scores = model.predict(pairs, show_progress_bar=False)

    normalized = [sigmoid(float(s)) for s in raw_scores]

    indexed = list(enumerate(normalized))
    indexed.sort(key=lambda x: x[1], reverse=True)
    top = indexed[: req.top_k]

    return RerankResponse(
        results=[RerankItem(index=i, score=s) for i, s in top]
    )
