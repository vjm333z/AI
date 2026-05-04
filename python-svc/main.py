# -*- coding: utf-8 -*-
"""
RecallAI Python 통합 서비스 — FastAPI 진입점.

- /rerank          : bge-reranker-v2-m3 (CPU/GPU 인퍼런스)
- /api/recording   : kt-call-bot 녹음 업로드 → STT → AIA_CALL_* 저장
- /api/admin/*     : 운영 관리
- /health          : 헬스체크 (reranker 모델 로드 상태 포함)

기동:
    uvicorn main:app --host 0.0.0.0 --port 8000

uvicorn workers는 1로 둘 것 — reranker 모델은 워커당 별도 메모리(~2GB)를 잡습니다.
"""

import logging
import threading
from contextlib import asynccontextmanager

from fastapi import FastAPI
from fastapi.middleware.cors import CORSMiddleware

from rerank_router import (
    router as rerank_router,
    load_model as load_rerank_model,
    is_model_ready as rerank_ready,
    get_load_error as rerank_error,
)
from stt_router import router as stt_router

logging.basicConfig(level=logging.INFO, format="%(asctime)s [%(levelname)s] %(name)s: %(message)s")
logger = logging.getLogger("python-svc")


@asynccontextmanager
async def lifespan(app: FastAPI):
    # reranker 모델은 별 thread에서 로드 — 30~60초 걸리므로 STT 쪽 startup을 막지 않음
    threading.Thread(target=load_rerank_model, daemon=True, name="rerank-loader").start()
    logger.info("python-svc started — reranker loading in background, STT ready immediately")
    yield


app = FastAPI(
    title="RecallAI Python Svc",
    version="1.0.0",
    description="reranker + STT 통합 서버",
    lifespan=lifespan,
)

app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],
    allow_methods=["*"],
    allow_headers=["*"],
)

app.include_router(rerank_router)
app.include_router(stt_router)


@app.get("/health")
def health():
    return {
        "status":          "ok",
        "rerank_ready":    rerank_ready(),
        "rerank_error":    rerank_error(),
    }
