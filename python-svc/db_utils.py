# -*- coding: utf-8 -*-
"""AIA_CALL_* 테이블 저장 유틸리티.

AIA_CALL_RECORDING (파일 메타) + AIA_CALL_TRANSCRIPT (STT) + AIA_CALL_ANALYSIS (요약)
3개 테이블에 트랜잭션으로 분산 INSERT/UPSERT.
"""

import json
import os
import re

DB_DEFAULT = {
    "host":     os.environ.get("DB_HOST", "localhost"),
    "port":     int(os.environ.get("DB_PORT", "3306")),
    "user":     os.environ.get("DB_USER", ""),
    "password": os.environ.get("DB_PASSWORD", ""),
    "database": os.environ.get("DB_NAME", "PMS"),
}

REG_USER_DEFAULT = "STT_AUTO"


def parse_report_fields(report: str) -> tuple:
    """summary.report 텍스트에서 caller_nm, contact_no 파싱.
    형식: '문의자 : XXX\n연락처: YYY\n문의내역: ...'
    """
    caller_nm, contact_no = None, None
    if not report:
        return caller_nm, contact_no
    for line in report.splitlines():
        m = re.match(r"문의자\s*:\s*(.+)", line)
        if m:
            v = m.group(1).strip()
            caller_nm = None if v in ("미확인", "") else v
        m = re.match(r"연락처\s*:\s*(.+)", line)
        if m:
            v = m.group(1).strip()
            contact_no = None if v in ("미확인", "") else v
    return caller_nm, contact_no


def _resolve_llm_model() -> str:
    """실제 사용한 LLM 모델명. summarize() 분기와 정합 유지."""
    provider = (os.environ.get("LLM_PROVIDER") or "openai").lower()
    if provider == "openai":
        return os.environ.get("OPENAI_MODEL", "gpt-4o-mini")
    if provider == "gemini":
        return os.environ.get("GEMINI_LLM_MODEL", "gemini-2.5-flash")
    return "llama-3.3-70b-versatile"


def save_to_aia(result: dict, db_cfg: dict) -> int:
    """STT 결과를 AIA_CALL_RECORDING / TRANSCRIPT / ANALYSIS 3개 테이블에 트랜잭션 저장.

    - BASE_FILE_NM 충돌 시 RECORDING은 UPSERT, TRANSCRIPT/ANALYSIS는 REPLACE
    - 반환: REC_SEQ_NO (실패 시 None)
    - summary 없거나 error 있으면 ANALYSIS는 건너뛰고 R_STATUS='STT'
    """
    try:
        import pymysql
    except ImportError:
        print("[DB] pymysql 미설치 — pip install pymysql")
        return None

    stt = result.get("stt") or {}
    summary = result.get("summary") or {}
    summary_ok = bool(summary) and "error" not in summary

    caller_nm, contact_no = parse_report_fields(summary.get("report", "") if summary_ok else "")

    rec_row = {
        "file_nm":      result.get("audio_file"),
        "base_file_nm": result.get("base_file_nm") or result.get("audio_file"),
        "sha256":       result.get("sha256"),
        "file_size":    result.get("file_size"),
        "file_path":    result.get("file_path"),
        "send_no":      result.get("caller_no"),
        "recv_no":      result.get("receiver_no"),
        "call_dt":      result.get("call_dt"),
        "channel_seq":  result.get("channel_seq"),
        "duration":     int(stt.get("duration_sec", 0) or 0) or None,
        "r_status":     "LMM" if summary_ok else "STT",
        "reg_user":     REG_USER_DEFAULT,
    }

    rec_sql = """
        INSERT INTO AIA_CALL_RECORDING
            (FILE_NM, BASE_FILE_NM, SHA256_HASH, FILE_SIZE, FILE_PATH,
             SEND_NO, RECV_NO, CALL_DT, CHANNEL_SEQ, DURATION_SEC,
             R_STATUS, REG_USER)
        VALUES
            (%(file_nm)s, %(base_file_nm)s, %(sha256)s, %(file_size)s, %(file_path)s,
             %(send_no)s, %(recv_no)s, %(call_dt)s, %(channel_seq)s, %(duration)s,
             %(r_status)s, %(reg_user)s)
        ON DUPLICATE KEY UPDATE
            FILE_NM      = VALUES(FILE_NM),
            SHA256_HASH  = COALESCE(VALUES(SHA256_HASH),  SHA256_HASH),
            FILE_SIZE    = COALESCE(VALUES(FILE_SIZE),    FILE_SIZE),
            FILE_PATH    = COALESCE(VALUES(FILE_PATH),    FILE_PATH),
            SEND_NO      = COALESCE(VALUES(SEND_NO),      SEND_NO),
            RECV_NO      = COALESCE(VALUES(RECV_NO),      RECV_NO),
            CALL_DT      = COALESCE(VALUES(CALL_DT),      CALL_DT),
            CHANNEL_SEQ  = COALESCE(VALUES(CHANNEL_SEQ),  CHANNEL_SEQ),
            DURATION_SEC = COALESCE(VALUES(DURATION_SEC), DURATION_SEC),
            R_STATUS     = VALUES(R_STATUS),
            MOD_USER     = VALUES(REG_USER),
            MOD_DT       = CURRENT_TIMESTAMP
    """

    trans_row = {
        "stt_text":      stt.get("text") or "",
        "segments_json": json.dumps(stt.get("segments") or [], ensure_ascii=False),
        "stt_model":     "whisper-large-v3",
        "reg_user":      REG_USER_DEFAULT,
    }

    trans_sql = """
        INSERT INTO AIA_CALL_TRANSCRIPT
            (REC_SEQ_NO, STT_TEXT, SEGMENTS_JSON, STT_MODEL, REG_USER)
        VALUES
            (%(rec_seq_no)s, %(stt_text)s, %(segments_json)s, %(stt_model)s, %(reg_user)s)
        ON DUPLICATE KEY UPDATE
            STT_TEXT      = VALUES(STT_TEXT),
            SEGMENTS_JSON = VALUES(SEGMENTS_JSON),
            STT_MODEL     = VALUES(STT_MODEL),
            MOD_USER      = VALUES(REG_USER),
            MOD_DT        = CURRENT_TIMESTAMP
    """

    if summary_ok:
        # FOLLOWUP_NOTE 에는 LLM이 생성한 추천 답변(summary.feedback) 저장 — 화면에서 sttFeedback로 노출
        analysis_row = {
            "cmpx_nm":        result.get("cmpx_nm"),
            "cust_nm":        summary.get("caller_nm") or caller_nm,
            "cust_phone":     contact_no,
            "inq_desc":       summary.get("report"),
            "ai_summary":     summary.get("summary"),
            "category_cd":    summary.get("category"),
            "urgency_cd":     summary.get("urgency_cd"),
            "system_cd":      summary.get("system_cd"),
            "system_tp":      summary.get("system_tp"),
            "followup_note":  summary.get("feedback"),
            "llm_model":      _resolve_llm_model(),
            "reg_user":       REG_USER_DEFAULT,
        }
        analysis_sql = """
            INSERT INTO AIA_CALL_ANALYSIS
                (REC_SEQ_NO, CMPX_NM, CUST_NM, CUST_PHONE, INQ_DESC, AI_SUMMARY,
                 CATEGORY_CD, URGENCY_CD, SYSTEM_CD, SYSTEM_TP, FOLLOWUP_NOTE,
                 LLM_MODEL, REG_USER)
            VALUES
                (%(rec_seq_no)s, %(cmpx_nm)s, %(cust_nm)s, %(cust_phone)s, %(inq_desc)s, %(ai_summary)s,
                 %(category_cd)s, %(urgency_cd)s, %(system_cd)s, %(system_tp)s, %(followup_note)s,
                 %(llm_model)s, %(reg_user)s)
            ON DUPLICATE KEY UPDATE
                CMPX_NM        = VALUES(CMPX_NM),
                CUST_NM        = VALUES(CUST_NM),
                CUST_PHONE     = VALUES(CUST_PHONE),
                INQ_DESC       = VALUES(INQ_DESC),
                AI_SUMMARY     = VALUES(AI_SUMMARY),
                CATEGORY_CD    = VALUES(CATEGORY_CD),
                URGENCY_CD     = VALUES(URGENCY_CD),
                SYSTEM_CD      = VALUES(SYSTEM_CD),
                SYSTEM_TP      = VALUES(SYSTEM_TP),
                FOLLOWUP_NOTE  = VALUES(FOLLOWUP_NOTE),
                LLM_MODEL      = VALUES(LLM_MODEL),
                MOD_USER       = VALUES(REG_USER),
                MOD_DT         = CURRENT_TIMESTAMP
        """

    conn = None
    try:
        conn = pymysql.connect(**db_cfg, charset="utf8mb4", autocommit=False)
        with conn.cursor() as cur:
            cur.execute(rec_sql, rec_row)
            cur.execute(
                "SELECT REC_SEQ_NO FROM AIA_CALL_RECORDING WHERE BASE_FILE_NM = %s",
                (rec_row["base_file_nm"],),
            )
            row = cur.fetchone()
            if not row:
                raise RuntimeError("REC_SEQ_NO 조회 실패 — RECORDING UPSERT 직후")
            rec_seq_no = row[0]

            cur.execute(trans_sql, dict(trans_row, rec_seq_no=rec_seq_no))

            if summary_ok:
                cur.execute(analysis_sql, dict(analysis_row, rec_seq_no=rec_seq_no))

        conn.commit()
        action = "+ANALYSIS" if summary_ok else "STT only"
        print(f"[DB] AIA UPSERT 완료 (REC_SEQ_NO={rec_seq_no}, {action})")
        return rec_seq_no
    except Exception as e:
        if conn:
            try:
                conn.rollback()
            except Exception:
                pass
        print(f"[DB] AIA UPSERT 실패: {e}")
        return None
    finally:
        if conn:
            conn.close()
