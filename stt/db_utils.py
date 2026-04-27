# -*- coding: utf-8 -*-
"""CALL_QUEUE DB 조회/저장 유틸리티."""

import re

DB_DEFAULT = {
    "host": "127.0.0.1",
    "port": 3307,
    "user": "recallai",
    "password": "recallai",
    "database": "recallai",
}


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


def lookup_caller_history(caller_no: str, db_cfg: dict) -> tuple:
    """CALL_QUEUE에서 동일 발신번호의 가장 최근 prop_cd/cmpx_cd 조회.
    반환: (prop_cd, cmpx_cd) or (None, None).
    """
    if not caller_no or not db_cfg:
        return None, None
    try:
        import pymysql
        conn = pymysql.connect(**db_cfg, charset="utf8mb4", autocommit=True)
        with conn.cursor() as cur:
            cur.execute("""
                SELECT prop_cd, cmpx_cd FROM CALL_QUEUE
                WHERE caller_no = %s AND prop_cd IS NOT NULL
                ORDER BY created_dt DESC LIMIT 1
            """, (caller_no,))
            row = cur.fetchone()
        conn.close()
        if row:
            return row[0], row[1]
    except Exception as e:
        print(f"[히스토리] 조회 실패: {e}")
    return None, None


def save_to_db(result: dict, db_cfg: dict) -> int:
    """CALL_QUEUE에 STT 결과 upsert. call_id 반환. 실패 시 None."""
    try:
        import pymysql
    except ImportError:
        print("[DB] pymysql 미설치 — pip install pymysql")
        return None

    summary = result.get("summary") or {}
    stt = result.get("stt") or {}
    report = summary.get("report", "")
    caller_nm, contact_no = parse_report_fields(report)

    row = {
        "audio_file":     result.get("audio_file"),
        "caller_no":      result.get("caller_no"),
        "receiver_no":    result.get("receiver_no"),
        "call_dt":        result.get("call_dt"),
        "prop_cd":        result.get("prop_cd"),
        "cmpx_cd":        result.get("cmpx_cd"),
        "call_duration":  int(stt.get("duration_sec", 0) or 0),
        "stt_raw":        stt.get("text"),
        "stt_report":     report or None,
        "stt_feedback":   summary.get("feedback"),
        "stt_summary":    summary.get("summary"),
        "caller_nm":      caller_nm,
        "contact_no":     contact_no,
        "category":       summary.get("category"),
        "resolve_status": summary.get("status"),
    }

    sql = """
        INSERT INTO CALL_QUEUE
            (audio_file, caller_no, receiver_no, call_dt, prop_cd, cmpx_cd, call_duration,
             stt_raw, stt_report, stt_feedback, stt_summary,
             caller_nm, contact_no, category, resolve_status)
        VALUES
            (%(audio_file)s, %(caller_no)s, %(receiver_no)s, %(call_dt)s, %(prop_cd)s, %(cmpx_cd)s, %(call_duration)s,
             %(stt_raw)s, %(stt_report)s, %(stt_feedback)s, %(stt_summary)s,
             %(caller_nm)s, %(contact_no)s, %(category)s, %(resolve_status)s)
        ON DUPLICATE KEY UPDATE
            prop_cd        = COALESCE(VALUES(prop_cd),        prop_cd),
            cmpx_cd        = COALESCE(VALUES(cmpx_cd),        cmpx_cd),
            call_duration  = COALESCE(VALUES(call_duration),  call_duration),
            stt_raw        = COALESCE(VALUES(stt_raw),        stt_raw),
            stt_report     = COALESCE(VALUES(stt_report),     stt_report),
            stt_feedback   = COALESCE(VALUES(stt_feedback),   stt_feedback),
            stt_summary    = COALESCE(VALUES(stt_summary),    stt_summary),
            caller_nm      = COALESCE(VALUES(caller_nm),      caller_nm),
            contact_no     = COALESCE(VALUES(contact_no),     contact_no),
            category       = COALESCE(VALUES(category),       category),
            resolve_status = COALESCE(VALUES(resolve_status), resolve_status),
            updated_dt     = CURRENT_TIMESTAMP
    """
    try:
        conn = pymysql.connect(**db_cfg, charset="utf8mb4", autocommit=True)
        with conn.cursor() as cur:
            cur.execute(sql, row)
            call_id = cur.lastrowid
        conn.close()
        action = "UPSERT(갱신)" if call_id == 0 else f"INSERT (call_id={call_id})"
        print(f"[DB] CALL_QUEUE {action} 완료")
        return call_id
    except Exception as e:
        print(f"[DB] UPSERT 실패: {e}")
        return None
