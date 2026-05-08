#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""
STT 라우터 — 공유 볼륨에 저장된 음성 파일을 Whisper로 전사.

메인 RAG 앱(Spring)이 호출. 파일 자체는 전송하지 않고 {@code DATA_DIR} 기준 상대 경로만 받음.
업로드·DB 저장·호텔 매칭·요약은 모두 Spring에서 처리.
"""

import os
import threading
from pathlib import Path

from fastapi import APIRouter, HTTPException
from pydantic import BaseModel

from transcribe_summarize import transcribe_groq

# ── 동시 Groq STT 호출 방지 ─────────────────────────────────
# Spring에도 동일 락이 있어 이중 안전망. Whisper API rate limit 보호용.
_PROCESS_LOCK = threading.Lock()

# ── 공유 볼륨 루트 ──────────────────────────────────────────
# docker-compose에서 Spring(/app/data)와 같은 named volume을 마운트.
# 로컬 dev에선 환경변수로 경로 오버라이드 가능.
DATA_DIR = Path(os.environ.get("DATA_DIR", "/app/data")).resolve()

router = APIRouter(tags=["stt"])


class SttRequest(BaseModel):
    """Spring SttClient가 보내는 요청 — DATA_DIR 기준 상대 경로."""
    relative_path: str


@router.post("/stt")
def stt_endpoint(req: SttRequest):
    """공유 볼륨에 이미 저장된 파일을 Whisper로 전사하고 verbose JSON 반환.

    응답: {text, segments[{start,end,text}], duration_sec, language, language_probability}
    """
    full = (DATA_DIR / req.relative_path).resolve()
    # path traversal 방어 — DATA_DIR 밖으로 나가는 경로는 거부
    if not str(full).startswith(str(DATA_DIR)):
        raise HTTPException(400, "DATA_DIR 외부 경로 접근은 허용되지 않습니다")
    if not full.exists():
        raise HTTPException(404, f"파일 없음: {req.relative_path}")

    with _PROCESS_LOCK:
        return transcribe_groq(str(full))
