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
import threading
from pathlib import Path
from contextlib import asynccontextmanager

# 동시 Groq API 호출 방지 — 파일 1개씩 순차 처리
_PROCESS_LOCK = threading.Lock()

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
    """AIA_CALL_RECORDING에 동일 파일명 존재 여부 확인."""
    try:
        import pymysql
        conn = pymysql.connect(**DB_CFG, charset="utf8mb4", autocommit=True)
        with conn.cursor() as cur:
            cur.execute(
                "SELECT 1 FROM AIA_CALL_RECORDING WHERE BASE_FILE_NM = %s LIMIT 1",
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
    """inbox/ 파일 → STT → AIA DB → recordings/ 이동. 동시 처리 방지 lock 포함."""
    try:
        with _PROCESS_LOCK:
            _process_recording_inner(file_path)
    except BaseException as e:
        import traceback
        print(f"[서버] process_recording 치명적 예외 ({file_path.name}): {e}", flush=True)
        traceback.print_exc()


def _process_recording_inner(file_path: Path):
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
            find_hotel_by_phone_lookup, find_hotel_from_text, add_alias,
            find_cmpx_from_text,
        )
        from db_utils import lookup_caller_history, save_to_aia
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

        # 3초 미만 통화 스킵 (잡음·오발신 등 파악 불가)
        duration = stt_result.get("duration_sec", 0)
        if duration < 3:
            print(f"[서버] 통화 {duration:.1f}초 — 3초 미만이라 스킵 ({file_path.name})")
            shutil.move(str(file_path), str(DONE_DIR / file_path.name))
            return

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
        if summary and "error" not in summary:
            from corrections import DAOL_RECEIVER_NOS as _DNOS
            _contact = None
            if receiver_no and receiver_no not in _DNOS:
                _contact = receiver_no
            elif caller_no and caller_no not in _DNOS:
                _contact = caller_no
            else:
                _contact = caller_no or receiver_no
            if _contact and summary.get("report"):
                _lines = []
                _seen_contact = False
                for _line in summary["report"].split("\n"):
                    if "연락처" in _line and ":" in _line:
                        if not _seen_contact:
                            _lines.append(f"연락처: {_contact}")
                            _seen_contact = True
                    else:
                        _lines.append(_line)
                summary["report"] = "\n".join(_lines)
                print(f"[연락처 주입] {_contact}")

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
            matched_hotel, alias_candidate = find_hotel_from_text(search_text, hotels)
            if matched_hotel:
                prop_cd      = matched_hotel.get("propCd")
                matched_cmpx = find_cmpx_from_text(search_text, matched_hotel)
                if matched_cmpx:
                    cmpx_cd = matched_cmpx.get("cmpxCd")
                if alias_candidate and hotels_path:
                    add_alias(hotels_path, prop_cd, alias_candidate)

        if matched_hotel and cmpx_cd is None:
            complexes = matched_hotel.get("complexes", [])
            if len(complexes) == 1:
                cmpx_cd = complexes[0].get("cmpxCd")
                if not matched_cmpx:
                    matched_cmpx = complexes[0]

        cmpx_nm = (matched_cmpx.get("cmpxNm") if matched_cmpx else None) \
                  or (matched_hotel.get("propShrtNm") if matched_hotel else None)

        import hashlib
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

        # JSON 저장
        output_path = RESULTS_DIR / f"{file_path.stem}.json"
        with open(output_path, "w", encoding="utf-8") as f:
            json.dump(result, f, ensure_ascii=False, indent=2)

        # DB 저장
        rec_seq_no = save_to_aia(result, DB_CFG)
        if rec_seq_no:
            result["rec_seq_no"] = rec_seq_no
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


@app.post("/api/admin/process-inbox")
async def process_inbox(background_tasks: BackgroundTasks):
    """inbox/ 에 있는 미처리 mp3 파일 전체를 백그라운드에서 처리."""
    files = list(INBOX_DIR.glob("*.mp3"))
    queued = []
    skipped = []
    for f in files:
        if is_already_processed(f.name):
            skipped.append(f.name)
        else:
            background_tasks.add_task(process_recording, f)
            queued.append(f.name)
    return {"queued": len(queued), "skipped": len(skipped), "files": queued}


@app.get("/health")
async def health():
    return {"status": "ok"}
