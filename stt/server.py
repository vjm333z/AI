#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""
AI 서버 — 녹음 파일 수신 API

kt-call-bot이 POST /api/recording 으로 mp3를 보내면
inbox/ 에 저장 후 백그라운드에서 STT → CALL_QUEUE 자동 처리.

실행:
    uvicorn server:app --host 0.0.0.0 --port 8001 --reload
"""

import os
import re
import shutil
from pathlib import Path
from contextlib import asynccontextmanager

from fastapi import FastAPI, File, Form, UploadFile, HTTPException, BackgroundTasks
from fastapi.responses import JSONResponse
from fastapi.middleware.cors import CORSMiddleware

# ── 경로 설정 ──────────────────────────────────────────────────
BASE_DIR     = Path(__file__).parent
INBOX_DIR    = BASE_DIR / "inbox"
DONE_DIR     = BASE_DIR / "recordings"
RESULTS_DIR  = BASE_DIR / "results"
DATA_DIR     = BASE_DIR.parent / "data"
HOTELS_JSON  = DATA_DIR / "hotels.json"

INBOX_DIR.mkdir(exist_ok=True)
DONE_DIR.mkdir(exist_ok=True)
RESULTS_DIR.mkdir(exist_ok=True)

# ── DB 기본값 (환경변수로 오버라이드 가능) ────────────────────
from db_utils import DB_DEFAULT
import db_utils

DB_CFG = {
    "host":     os.environ.get("DB_HOST",     DB_DEFAULT["host"]),
    "port":     int(os.environ.get("DB_PORT", DB_DEFAULT["port"])),
    "user":     os.environ.get("DB_USER",     DB_DEFAULT["user"]),
    "password": os.environ.get("DB_PASSWORD", DB_DEFAULT["password"]),
    "database": os.environ.get("DB_NAME",     DB_DEFAULT["database"]),
}


# ── 파일명 정규화 (kt-call-bot이 붙이는 (1),(2) 접미사 제거) ──
def normalize_filename(filename: str) -> str:
    return re.sub(r"\s*\(\d+\)(\.[^.]+)$", r"\1", filename)


# ── 이미 처리된 파일 여부 확인 ─────────────────────────────────
def is_already_processed(base_filename: str) -> bool:
    """CALL_QUEUE에 동일 파일명 존재 여부 확인."""
    try:
        import pymysql
        conn = pymysql.connect(**DB_CFG, charset="utf8mb4", autocommit=True)
        with conn.cursor() as cur:
            cur.execute(
                "SELECT 1 FROM CALL_QUEUE WHERE audio_file = %s LIMIT 1",
                (base_filename,)
            )
            row = cur.fetchone()
        conn.close()
        return row is not None
    except Exception:
        # DB 연결 실패 시 파일 존재 여부를 results/ 폴더로 판단
        stem = Path(base_filename).stem
        return (RESULTS_DIR / f"{stem}.json").exists()


# ── 백그라운드 STT 파이프라인 ──────────────────────────────────
def process_recording(file_path: Path):
    """inbox/ 파일 → STT → CALL_QUEUE → recordings/ 이동."""
    import sys
    # 같은 디렉토리의 모듈 import 보장
    if str(BASE_DIR) not in sys.path:
        sys.path.insert(0, str(BASE_DIR))

    try:
        from transcribe_summarize import (
            parse_filename_meta, transcribe_groq,
            summarize, save_markdown_report, _inject_caller_contact, _inject_hotel_name,
        )
        from hotel_matcher import (
            load_hotels, load_phone_lookup, build_alias_pairs,
            fix_hotel_names, find_hotel_by_call_no,
            find_hotel_by_phone_lookup, find_hotel_from_text,
            find_cmpx_from_text,
        )
        from db_utils import lookup_caller_history, save_to_db
    except ImportError as e:
        print(f"[서버] import 실패: {e}")
        return

    import json

    audio_path = str(file_path)
    print(f"[서버] 처리 시작: {file_path.name}")

    try:
        # 메타 파싱
        meta        = parse_filename_meta(audio_path)
        caller_no   = meta["caller_no"]
        receiver_no = meta["receiver_no"]
        call_dt     = meta["call_dt"]
        print(f"[메타] caller_no={caller_no}, receiver_no={receiver_no}, call_dt={call_dt}")

        # 호텔 데이터 로드
        hotels_path  = str(HOTELS_JSON) if HOTELS_JSON.exists() else None
        hotels       = load_hotels(hotels_path) if hotels_path else []
        alias_pairs  = build_alias_pairs(hotels)
        phone_lookup = {}
        if hotels_path:
            lookup_path  = str(Path(hotels_path).parent / "phone_lookup.json")
            phone_lookup = load_phone_lookup(lookup_path)

        # STT
        stt_result = transcribe_groq(audio_path)

        # 호텔명 보정
        if alias_pairs:
            stt_result["text"] = fix_hotel_names(stt_result["text"], alias_pairs)
            for seg in stt_result.get("segments", []):
                seg["text"] = fix_hotel_names(seg["text"], alias_pairs)

        # 요약 (phone lookup 기반 빠른 사전 매칭 후 컨텍스트 전달)
        pre_hotel, pre_cmpx = None, None
        pre_hotel, pre_cmpx = find_hotel_by_call_no(caller_no, hotels)
        if not pre_hotel:
            pre_hotel, pre_cmpx = find_hotel_by_call_no(receiver_no, hotels)
        if not pre_hotel and phone_lookup:
            pre_hotel = find_hotel_by_phone_lookup(caller_no, phone_lookup, hotels)
        if not pre_hotel and phone_lookup and receiver_no:
            pre_hotel = find_hotel_by_phone_lookup(receiver_no, phone_lookup, hotels)
        hotel_display = (pre_cmpx.get("cmpxNm") if pre_cmpx else None) or (pre_hotel.get("propShrtNm") if pre_hotel else None)
        from corrections import DAOL_RECEIVER_NOS
        summarize_ctx = {
            "caller_no":   caller_no,
            "receiver_no": receiver_no,
            "call_dt":     call_dt,
            "hotel_name":  hotel_display,
            "prop_cd":     pre_hotel.get("propCd") if pre_hotel else None,
            "daol_nos":    DAOL_RECEIVER_NOS,
        }
        summary = summarize(stt_result["segments"], context=summarize_ctx)
        if summary and "error" not in summary and caller_no:
            summary["report"] = _inject_caller_contact(summary.get("report", ""), caller_no, receiver_no)

        # 호텔 매칭
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

        if not matched_hotel and phone_lookup:
            matched_hotel = find_hotel_by_phone_lookup(caller_no, phone_lookup, hotels)
            if matched_hotel:
                prop_cd = matched_hotel.get("propCd")

        if not matched_hotel and phone_lookup and receiver_no:
            matched_hotel = find_hotel_by_phone_lookup(receiver_no, phone_lookup, hotels)
            if matched_hotel:
                prop_cd = matched_hotel.get("propCd")

        if not matched_hotel and caller_no:
            hist_prop, hist_cmpx = lookup_caller_history(caller_no, DB_CFG)
            if hist_prop:
                prop_cd = hist_prop
                cmpx_cd = hist_cmpx

        if not matched_hotel and summary and "error" not in summary:
            search_text = " ".join(filter(None, [
                summary.get("speaker_B", ""),
                summary.get("report", ""),
                stt_result.get("text", "")[:500],
            ]))
            matched_hotel = find_hotel_from_text(search_text, hotels)
            if matched_hotel:
                prop_cd      = matched_hotel.get("propCd")
                matched_cmpx = find_cmpx_from_text(search_text, matched_hotel)
                if matched_cmpx:
                    cmpx_cd = matched_cmpx.get("cmpxCd")

        if matched_hotel and cmpx_cd is None:
            complexes = matched_hotel.get("complexes", [])
            if len(complexes) == 1:
                cmpx_cd = complexes[0].get("cmpxCd")

        if matched_hotel and summary and "error" not in summary:
            hotel_nm = (matched_cmpx.get("cmpxNm") if matched_cmpx else None) or matched_hotel.get("propShrtNm", "")
            summary["report"] = _inject_hotel_name(summary.get("report", ""), hotel_nm)

        result = {
            "audio_file":  file_path.name,
            "caller_no":   caller_no,
            "receiver_no": receiver_no,
            "call_dt":     call_dt,
            "prop_cd":     prop_cd,
            "cmpx_cd":     cmpx_cd,
            "stt":         stt_result,
            "summary":     summary,
        }

        # JSON 저장
        output_path = RESULTS_DIR / f"{file_path.stem}.json"
        with open(output_path, "w", encoding="utf-8") as f:
            json.dump(result, f, ensure_ascii=False, indent=2)

        # DB 저장
        call_id = save_to_db(result, DB_CFG)
        if call_id:
            result["call_id"] = call_id
            with open(output_path, "w", encoding="utf-8") as f:
                json.dump(result, f, ensure_ascii=False, indent=2)

        # MD 리포트
        from transcribe_summarize import save_markdown_report
        md_path = str(output_path.with_suffix(".md"))
        save_markdown_report(md_path, file_path.name, stt_result, summary)

        # 처리 완료 → recordings/ 이동
        shutil.move(str(file_path), DONE_DIR / file_path.name)
        print(f"[서버] 처리 완료: {file_path.name} → recordings/")

    except Exception as e:
        import traceback
        print(f"[서버] 처리 실패 ({file_path.name}): {e}")
        traceback.print_exc()


# ── FastAPI 앱 ─────────────────────────────────────────────────
@asynccontextmanager
async def lifespan(app: FastAPI):
    print(f"[서버] 시작 | inbox: {INBOX_DIR}, DB: {DB_CFG['host']}:{DB_CFG['port']}")
    yield

app = FastAPI(title="RecallAI STT Server", version="1.0.0", lifespan=lifespan)

app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],
    allow_methods=["*"],
    allow_headers=["*"],
)


@app.post("/api/recording")
async def upload_recording(
    background_tasks: BackgroundTasks,
    file: UploadFile = File(...),
    sha256: str = Form(None),   # kt-call-bot이 보내는 감사용 해시 (선택)
    force: bool = False,        # True 시 이미 처리된 파일도 재처리
):
    """
    kt-call-bot이 녹음 파일을 업로드하는 엔드포인트.
    파일을 inbox/ 에 저장 후 백그라운드에서 STT 파이프라인 실행.

    ?force=true — 이미 처리된 파일도 재처리 (STT 품질 개선 등 수동 재처리용).
    CALL_QUEUE는 upsert이므로 기존 레코드를 덮어씁니다.
    """
    original_name = file.filename or "unknown.mp3"
    base_name     = normalize_filename(original_name)

    # 중복 체크 (force=true 이면 건너뜀)
    if not force and is_already_processed(base_name):
        return JSONResponse(
            status_code=409,
            content={"exists": True, "filename": base_name,
                     "message": "이미 처리된 파일입니다. 재처리하려면 ?force=true 를 사용하세요."}
        )

    # inbox 저장 — force 시 덮어쓰기 허용
    dest = INBOX_DIR / base_name
    if not force and dest.exists():
        return JSONResponse(
            status_code=409,
            content={"exists": True, "filename": base_name,
                     "message": "처리 대기 중인 파일입니다."}
        )

    content = await file.read()
    with open(dest, "wb") as f:
        f.write(content)

    print(f"[서버] 파일 수신: {base_name} ({len(content):,} bytes){' [강제 재처리]' if force else ''}")

    # 백그라운드 처리
    background_tasks.add_task(process_recording, dest)

    return JSONResponse(
        status_code=202,
        content={"accepted": True, "filename": base_name, "force": force,
                 "message": "수신 완료. 백그라운드에서 처리 중입니다." + (" (강제 재처리)" if force else "")}
    )


@app.get("/api/recording/exists")
async def check_exists(filename: str):
    """
    kt-call-bot 조기 중단용 — 이미 처리된 파일인지 확인.
    """
    base_name = normalize_filename(filename)
    exists    = is_already_processed(base_name)

    # inbox 대기 중인 것도 exists로 처리
    if not exists and (INBOX_DIR / base_name).exists():
        exists = True

    return {"exists": exists, "filename": base_name}


@app.get("/api/admin/status")
async def status():
    """파이프라인 현황 — inbox 대기 수, 처리 완료 수."""
    inbox_count   = len(list(INBOX_DIR.glob("*.mp3")))
    done_count    = len(list(DONE_DIR.glob("*.mp3")))
    results_count = len(list(RESULTS_DIR.glob("*.json")))
    return {
        "inbox_pending": inbox_count,
        "processed":     done_count,
        "results":       results_count,
    }


@app.get("/api/admin/recent")
async def recent(limit: int = 20):
    """CALL_QUEUE 최근 처리 내역 — 처리 결과 확인·디버깅용."""
    try:
        import pymysql
        conn = pymysql.connect(**DB_CFG, charset="utf8mb4", autocommit=True)
        with conn.cursor(pymysql.cursors.DictCursor) as cur:
            cur.execute("""
                SELECT call_id, audio_file, caller_no, receiver_no, call_dt,
                       prop_cd, category, resolve_status, caller_nm,
                       LEFT(stt_report, 120) AS stt_report_preview,
                       LEFT(stt_feedback, 120) AS stt_feedback_preview,
                       created_dt, updated_dt
                FROM CALL_QUEUE
                ORDER BY created_dt DESC
                LIMIT %s
            """, (min(limit, 100),))
            rows = cur.fetchall()
        conn.close()
        return {"count": len(rows), "items": rows}
    except Exception as e:
        return JSONResponse(status_code=500, content={"error": str(e)})


@app.get("/api/queue")
async def get_queue(status: str = "PENDING", limit: int = 50):
    """PMS UI용 — CALL_QUEUE 미처리 대기건 목록 (전체 필드)."""
    try:
        import pymysql
        conn = pymysql.connect(**DB_CFG, charset="utf8mb4", autocommit=True)
        with conn.cursor(pymysql.cursors.DictCursor) as cur:
            cur.execute("""
                SELECT call_id, audio_file, caller_no, receiver_no, call_dt,
                       prop_cd, cmpx_cd, category, resolve_status, caller_nm,
                       call_duration, stt_report, stt_feedback, stt_summary, created_dt, updated_dt
                FROM CALL_QUEUE
                WHERE (resolve_status IS NULL OR resolve_status != 'REGISTERED')
                ORDER BY created_dt DESC
                LIMIT %s
            """, (min(limit, 200),))
            rows = cur.fetchall()
        conn.close()
        for r in rows:
            for k, v in r.items():
                if hasattr(v, 'isoformat'):
                    r[k] = v.isoformat()
        return {"count": len(rows), "items": rows}
    except Exception as e:
        return JSONResponse(status_code=500, content={"error": str(e)})


@app.put("/api/queue/{call_id}/status")
async def update_queue_status(call_id: int, body: dict):
    """PMS UI용 — 대기건 상태 업데이트 (등록완료·스킵 등)."""
    new_status = body.get("status", "REGISTERED")
    try:
        import pymysql
        conn = pymysql.connect(**DB_CFG, charset="utf8mb4", autocommit=True)
        with conn.cursor() as cur:
            cur.execute(
                "UPDATE CALL_QUEUE SET resolve_status = %s, updated_dt = NOW() WHERE call_id = %s",
                (new_status, call_id)
            )
            affected = cur.rowcount
        conn.close()
        return {"updated": affected, "call_id": call_id, "status": new_status}
    except Exception as e:
        return JSONResponse(status_code=500, content={"error": str(e)})


@app.get("/api/queue/caller-history")
async def caller_history(phone: str, exclude_id: int = None, limit: int = 10):
    """동일 발신/수신 번호로 온 이전 통화 이력 조회."""
    if not phone:
        return {"count": 0, "items": []}
    try:
        import pymysql
        conn = pymysql.connect(**DB_CFG, charset="utf8mb4", autocommit=True)
        with conn.cursor(pymysql.cursors.DictCursor) as cur:
            # 발신자 OR 수신자 번호가 일치하는 건 (현재 건 제외)
            sql = """
                SELECT call_id, audio_file, caller_no, receiver_no, call_dt,
                       call_duration, prop_cd, cmpx_cd, resolve_status,
                       stt_summary, LEFT(stt_report, 300) AS stt_report_preview,
                       created_dt
                FROM CALL_QUEUE
                WHERE (caller_no = %s OR receiver_no = %s)
            """
            params = [phone, phone]
            if exclude_id:
                sql += " AND call_id != %s"
                params.append(exclude_id)
            sql += " ORDER BY call_dt DESC LIMIT %s"
            params.append(min(limit, 50))
            cur.execute(sql, params)
            rows = cur.fetchall()
        conn.close()
        for r in rows:
            for k, v in r.items():
                if hasattr(v, "isoformat"):
                    r[k] = v.isoformat()
        return {"count": len(rows), "phone": phone, "items": rows}
    except Exception as e:
        return JSONResponse(status_code=500, content={"error": str(e)})


@app.get("/health")
async def health():
    return {"status": "ok"}
