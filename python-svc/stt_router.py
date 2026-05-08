#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""
STT 라우터 — Spring이 공유 볼륨에 저장한 녹음 파일을 STT 파이프라인으로 처리.

진입: POST /process {relative_path}
처리: STT → 호텔명 보정 → 요약 LLM → 호텔 매칭 → AIA_CALL_* UPSERT
"""

import hashlib
import json
import os
import shutil
import threading
from pathlib import Path

from fastapi import APIRouter, BackgroundTasks, HTTPException
from fastapi.responses import JSONResponse
from pydantic import BaseModel

from db_utils import DB_DEFAULT, save_to_aia
from transcribe_summarize import (
    parse_filename_meta, transcribe_groq, summarize, save_markdown_report,
    _inject_caller_contact,
)
from hotel_matcher import (
    load_hotels, build_alias_pairs, fix_hotel_names,
    find_hotel_by_call_no,
    find_hotel_from_text, find_cmpx_from_text, add_alias,
)
from corrections import DAOL_RECEIVER_NOS

# Groq API 동시 호출 방지 — 파일 1개씩 순차 처리
_PROCESS_LOCK = threading.Lock()

# Spring과 같은 공유 볼륨(/app/data) 가정. recordings/ 하위가 라이프사이클 루트.
DATA_ROOT    = Path(os.environ.get("DATA_DIR", "/app/data")).resolve()
INBOX_DIR    = DATA_ROOT / "recordings" / "inbox"
DONE_DIR     = DATA_ROOT / "recordings" / "done"
RESULTS_DIR  = DATA_ROOT / "recordings" / "results"
HOTELS_JSON  = DATA_ROOT / "hotels.json"

INBOX_DIR.mkdir(parents=True, exist_ok=True)
DONE_DIR.mkdir(parents=True, exist_ok=True)
RESULTS_DIR.mkdir(parents=True, exist_ok=True)

# AIA DB 자격증명 — docker-compose의 python-svc 환경변수로 주입
DB_CFG = {
    "host":     os.environ.get("DB_HOST",     DB_DEFAULT["host"]),
    "port":     int(os.environ.get("DB_PORT", DB_DEFAULT["port"])),
    "user":     os.environ.get("DB_USER",     DB_DEFAULT["user"]),
    "password": os.environ.get("DB_PASSWORD", DB_DEFAULT["password"]),
    "database": os.environ.get("DB_NAME",     DB_DEFAULT["database"]),
}


def process_recording(file_path: Path):
    """파이프라인 진입점. 락으로 직렬화하고 어떤 예외도 워커를 죽이지 않게 흡수."""
    try:
        with _PROCESS_LOCK:
            _process_recording_inner(file_path)
    except BaseException as e:
        import traceback
        print(f"[서버] process_recording 치명적 예외 ({file_path.name}): {e}", flush=True)
        traceback.print_exc()


def _process_recording_inner(file_path: Path):
    audio_path = str(file_path)
    print(f"[서버] 처리 시작: {file_path.name}")

    try:
        meta        = parse_filename_meta(audio_path)
        caller_no   = meta["caller_no"]
        receiver_no = meta["receiver_no"]
        call_dt     = meta["call_dt"]
        print(f"[메타] caller_no={caller_no}, receiver_no={receiver_no}, call_dt={call_dt}")

        hotels_path  = str(HOTELS_JSON) if HOTELS_JSON.exists() else None
        hotels       = load_hotels(hotels_path) if hotels_path else []
        alias_pairs  = build_alias_pairs(hotels)

        stt_result = transcribe_groq(audio_path)

        # 3초 미만은 잡음·오발신으로 간주하고 스킵
        duration = stt_result.get("duration_sec", 0)
        if duration < 3:
            print(f"[서버] 통화 {duration:.1f}초 — 3초 미만이라 스킵 ({file_path.name})")
            shutil.move(str(file_path), str(DONE_DIR / file_path.name))
            return

        # STT 텍스트에 호텔명 alias 보정 적용
        if alias_pairs:
            stt_result["text"] = fix_hotel_names(stt_result["text"], alias_pairs)
            for seg in stt_result.get("segments", []):
                seg["text"] = fix_hotel_names(seg["text"], alias_pairs)

        # 번호 기반 빠른 매칭으로 호텔명을 LLM 컨텍스트에 미리 넣어 정확도 향상
        pre_hotel, pre_cmpx = find_hotel_by_call_no(caller_no, hotels)
        if not pre_hotel:
            pre_hotel, pre_cmpx = find_hotel_by_call_no(receiver_no, hotels)
        hotel_display = (pre_cmpx.get("cmpxNm") if pre_cmpx else None) \
                        or (pre_hotel.get("propShrtNm") if pre_hotel else None)

        summarize_ctx = {
            "caller_no":   caller_no,
            "receiver_no": receiver_no,
            "call_dt":     call_dt,
            "hotel_name":  hotel_display,
            "prop_cd":     pre_hotel.get("propCd") if pre_hotel else None,
            "daol_nos":    DAOL_RECEIVER_NOS,
        }
        summary = summarize(stt_result["segments"], context=summarize_ctx)

        # 다올 제외한 상대측 번호를 report의 "연락처:" 줄에 주입
        if summary and "error" not in summary and summary.get("report"):
            summary["report"] = _inject_caller_contact(summary["report"], caller_no, receiver_no)

        # 발신측이 다올 비전이면 outbound 통화 — caller_nm 은 다올 직원일 가능성 높음. 무효화.
        if summary and "error" not in summary and caller_no in DAOL_RECEIVER_NOS:
            summary["caller_nm"] = None

        # 호텔 매칭 캐스케이드: 발신번호 → 수신번호 → 텍스트 fuzzy
        prop_cd, cmpx_cd, matched_hotel, matched_cmpx = None, None, None, None

        matched_hotel, matched_cmpx = find_hotel_by_call_no(caller_no, hotels)
        if matched_hotel:
            prop_cd = matched_hotel.get("propCd")
            cmpx_cd = matched_cmpx.get("cmpxCd") if matched_cmpx else None
        if not matched_hotel:
            matched_hotel, matched_cmpx = find_hotel_by_call_no(receiver_no, hotels)
            if matched_hotel:
                prop_cd = matched_hotel.get("propCd")
                cmpx_cd = matched_cmpx.get("cmpxCd") if matched_cmpx else None

        if not matched_hotel and summary and "error" not in summary:
            search_text = " ".join(filter(None, [
                summary.get("speaker_B", ""),
                summary.get("report", ""),
                stt_result.get("text", "")[:500],
            ]))
            matched_hotel, alias_candidate = find_hotel_from_text(search_text, hotels)
            if matched_hotel:
                prop_cd      = matched_hotel.get("propCd")
                matched_cmpx = find_cmpx_from_text(search_text, matched_hotel)
                if matched_cmpx:
                    cmpx_cd = matched_cmpx.get("cmpxCd")
                if alias_candidate and hotels_path:
                    add_alias(hotels_path, prop_cd, alias_candidate)

        # 호텔에 complex가 하나뿐이면 자동 선택
        if matched_hotel and cmpx_cd is None:
            complexes = matched_hotel.get("complexes", [])
            if len(complexes) == 1:
                cmpx_cd = complexes[0].get("cmpxCd")
                if not matched_cmpx:
                    matched_cmpx = complexes[0]

        cmpx_nm = (matched_cmpx.get("cmpxNm") if matched_cmpx else None) \
                  or (matched_hotel.get("propShrtNm") if matched_hotel else None)

        file_bytes  = file_path.read_bytes()
        sha256_hash = hashlib.sha256(file_bytes).hexdigest()

        result = {
            "audio_file":   file_path.name,
            "base_file_nm": file_path.name,
            "file_path":    str(file_path),
            "file_size":    file_path.stat().st_size,
            "sha256":       sha256_hash,
            "caller_no":    caller_no,
            "receiver_no":  receiver_no,
            "call_dt":      call_dt,
            "channel_seq":  int(file_path.stem.split("-")[-1]) if file_path.stem.split("-")[-1].isdigit() else None,
            "prop_cd":      prop_cd,
            "cmpx_cd":      cmpx_cd,
            "cmpx_nm":      cmpx_nm,
            "stt":          stt_result,
            "summary":      summary,
        }

        output_path = RESULTS_DIR / f"{file_path.stem}.json"
        with open(output_path, "w", encoding="utf-8") as f:
            json.dump(result, f, ensure_ascii=False, indent=2)

        # AIA_CALL_RECORDING + TRANSCRIPT + ANALYSIS UPSERT
        rec_seq_no = save_to_aia(result, DB_CFG)
        if rec_seq_no:
            result["rec_seq_no"] = rec_seq_no
            with open(output_path, "w", encoding="utf-8") as f:
                json.dump(result, f, ensure_ascii=False, indent=2)

        save_markdown_report(str(output_path.with_suffix(".md")), file_path.name, stt_result, summary)

        shutil.move(str(file_path), DONE_DIR / file_path.name)
        print(f"[서버] 처리 완료: {file_path.name} → recordings/")

    except Exception as e:
        import traceback
        print(f"[서버] 처리 실패 ({file_path.name}): {e}")
        traceback.print_exc()


router = APIRouter(tags=["stt"])


class ProcessRequest(BaseModel):
    """Spring 트리거 요청 — DATA_ROOT 기준 상대경로 (예: 'recordings/inbox/foo.mp3')."""
    relative_path: str


@router.post("/process")
async def process_endpoint(req: ProcessRequest, background_tasks: BackgroundTasks):
    """Spring이 저장한 파일을 백그라운드 STT 파이프라인에 큐잉."""
    target = (DATA_ROOT / req.relative_path).resolve()

    if not str(target).startswith(str(DATA_ROOT)):
        raise HTTPException(status_code=400, detail="DATA_ROOT 외부 경로 거부")
    if not target.exists():
        raise HTTPException(status_code=404, detail=f"파일 없음: {req.relative_path}")

    background_tasks.add_task(process_recording, target)
    return JSONResponse(
        status_code=202,
        content={"accepted": True, "filename": target.name},
    )


@router.get("/api/admin/status")
async def status():
    """파이프라인 현황 — inbox 대기 / 처리 완료 / 결과 JSON 수."""
    return {
        "inbox_pending": len(list(INBOX_DIR.glob("*.mp3"))),
        "processed":     len(list(DONE_DIR.glob("*.mp3"))),
        "results":       len(list(RESULTS_DIR.glob("*.json"))),
    }


@router.post("/api/admin/process-inbox")
async def process_inbox(background_tasks: BackgroundTasks):
    """inbox/ 잔여 파일 일괄 처리 (Spring 다운 등 복구용)."""
    files = list(INBOX_DIR.glob("*.mp3"))
    for f in files:
        background_tasks.add_task(process_recording, f)
    return {"queued": len(files), "files": [f.name for f in files]}
