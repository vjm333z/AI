#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""
4월녹음 폴더에서 mp3를 골라 STT 처리하는 로컬 배치 스크립트.

사용법:
    python scripts/batch_process.py               # 기본 3개 처리
    python scripts/batch_process.py --batch 5     # 5개 처리
    python scripts/batch_process.py --save-db     # AIA_CALL_* DB에 저장도 함께
    python scripts/batch_process.py --list        # 미처리 목록만 출력
    python scripts/batch_process.py --src "D:/다른폴더"
"""

import argparse, json, shutil, sys
from pathlib import Path

# 부모 폴더(python-svc/) 모듈 import 가능하게
SVC_DIR = Path(__file__).parent.parent
sys.path.insert(0, str(SVC_DIR))

from stt_router import RESULTS_DIR, DONE_DIR, DATA_DIR, HOTELS_JSON
from transcribe_summarize import (
    parse_filename_meta, transcribe_groq, summarize,
    save_markdown_report, _inject_caller_contact,
)
from hotel_matcher import (
    load_hotels, load_phone_lookup, build_alias_pairs, fix_hotel_names,
    find_hotel_by_call_no, find_hotel_by_phone_lookup,
    find_hotel_from_text, find_cmpx_from_text, add_alias,
)
from corrections import DAOL_RECEIVER_NOS
from db_utils import save_to_aia, DB_DEFAULT

DEFAULT_SRC = Path(r"C:\Users\eh\Downloads\4월녹음")

RESULTS_DIR.mkdir(exist_ok=True)
DONE_DIR.mkdir(exist_ok=True)


def already_done(stem: str) -> bool:
    return (RESULTS_DIR / f"{stem}.json").exists()


def list_pending(src: Path) -> list:
    files = sorted(src.glob("*.mp3")) + sorted(src.glob("*.m4a")) + sorted(src.glob("*.wav"))
    return [f for f in files if not already_done(f.stem)]


def process_file(audio_path: Path, save_db: bool, db_cfg: dict):
    print(f"\n{'='*60}\n처리 시작: {audio_path.name}\n{'='*60}")

    meta        = parse_filename_meta(str(audio_path))
    caller_no   = meta["caller_no"]
    receiver_no = meta["receiver_no"]
    call_dt     = meta["call_dt"]
    print(f"[메타] caller={caller_no}, receiver={receiver_no}, dt={call_dt}")

    hotels_path  = str(HOTELS_JSON) if HOTELS_JSON.exists() else None
    hotels       = load_hotels(hotels_path) if hotels_path else []
    alias_pairs  = build_alias_pairs(hotels)
    phone_lookup = {}
    if hotels_path:
        phone_lookup = load_phone_lookup(str(Path(hotels_path).parent / "phone_lookup.json"))

    stt_result = transcribe_groq(str(audio_path))
    if alias_pairs:
        stt_result["text"] = fix_hotel_names(stt_result["text"], alias_pairs)
        for seg in stt_result.get("segments", []):
            seg["text"] = fix_hotel_names(seg["text"], alias_pairs)

    # 요약 (사전 호텔 매칭으로 컨텍스트 전달)
    pre_hotel, pre_cmpx = find_hotel_by_call_no(caller_no, hotels) or (None, None)
    if not pre_hotel:
        pre_hotel, pre_cmpx = find_hotel_by_call_no(receiver_no, hotels) or (None, None)
    if not pre_hotel and phone_lookup:
        pre_hotel = find_hotel_by_phone_lookup(caller_no, phone_lookup, hotels)
    hotel_display = (pre_cmpx.get("cmpxNm") if pre_cmpx else None) \
                    or (pre_hotel.get("propShrtNm") if pre_hotel else None)

    summary = summarize(stt_result["segments"], context={
        "caller_no": caller_no, "receiver_no": receiver_no,
        "call_dt": call_dt, "hotel_name": hotel_display, "daol_nos": DAOL_RECEIVER_NOS,
    })
    if summary and "error" not in summary:
        summary["report"] = _inject_caller_contact(
            summary.get("report", ""), caller_no, receiver_no)

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
    if not matched_hotel and summary and "error" not in summary:
        search_text = " ".join(filter(None, [
            summary.get("speaker_B", ""), summary.get("report", ""),
            stt_result.get("text", "")[:500],
        ]))
        matched_hotel, alias_cand = find_hotel_from_text(search_text, hotels)
        if matched_hotel:
            prop_cd      = matched_hotel.get("propCd")
            matched_cmpx = find_cmpx_from_text(search_text, matched_hotel)
            if matched_cmpx:
                cmpx_cd = matched_cmpx.get("cmpxCd")
            if alias_cand and hotels_path:
                add_alias(hotels_path, prop_cd, alias_cand)
    if matched_hotel and cmpx_cd is None:
        complexes = matched_hotel.get("complexes", [])
        if len(complexes) == 1:
            cmpx_cd = complexes[0].get("cmpxCd")
            if not matched_cmpx:
                matched_cmpx = complexes[0]

    cmpx_nm = (matched_cmpx.get("cmpxNm") if matched_cmpx else None) \
              or (matched_hotel.get("propShrtNm") if matched_hotel else None)

    result = {
        "audio_file":   audio_path.name,
        "base_file_nm": audio_path.name,
        "caller_no":    caller_no,
        "receiver_no":  receiver_no,
        "call_dt":      call_dt,
        "prop_cd":      prop_cd,
        "cmpx_cd":      cmpx_cd,
        "cmpx_nm":      cmpx_nm,
        "stt":          stt_result,
        "summary":      summary,
    }

    out_json = RESULTS_DIR / f"{audio_path.stem}.json"
    with open(out_json, "w", encoding="utf-8") as f:
        json.dump(result, f, ensure_ascii=False, indent=2)
    save_markdown_report(str(out_json.with_suffix(".md")), audio_path.name, stt_result, summary)

    if save_db and db_cfg:
        rec_seq_no = save_to_aia(result, db_cfg)
        if rec_seq_no:
            result["rec_seq_no"] = rec_seq_no
            with open(out_json, "w", encoding="utf-8") as f:
                json.dump(result, f, ensure_ascii=False, indent=2)
            print(f"[DB] rec_seq_no={rec_seq_no}")

    shutil.copy2(str(audio_path), DONE_DIR / audio_path.name)

    if summary and "error" not in summary:
        print(f"  호텔  : {cmpx_nm or '미매칭'}")
        print(f"  문의자: {summary.get('caller_nm', '?')}")
        print(f"  시스템: {summary.get('system_cd')}  내용: {summary.get('system_con')}  유형: {summary.get('system_tp')}  긴급도: {summary.get('urgency_cd')}")
        print(f"  상태  : {summary.get('status')}")
    print(f"  저장  : {out_json.name}")


def main():
    parser = argparse.ArgumentParser(description="4월녹음 배치 STT 처리")
    parser.add_argument("--src",     default=str(DEFAULT_SRC))
    parser.add_argument("--batch",   type=int, default=3, help="처리 파일 수 (기본 3)")
    parser.add_argument("--save-db", action="store_true",  help="AIA_CALL_* DB에 저장")
    parser.add_argument("--list",    action="store_true",  help="미처리 목록만 출력")
    args = parser.parse_args()

    src = Path(args.src)
    if not src.exists():
        sys.exit(f"소스 폴더 없음: {src}")

    pending = list_pending(src)
    print(f"[배치] 미처리: {len(pending)}개  소스: {src}")

    if args.list or not pending:
        for p in pending:
            print(f"  - {p.name}")
        if not pending:
            print("처리할 파일 없음")
        return

    targets = pending[: args.batch]
    print(f"[배치] 처리 대상: {len(targets)}개")

    db_cfg = None
    if args.save_db:
        db_cfg = dict(DB_DEFAULT)

    for audio_path in targets:
        try:
            process_file(audio_path, save_db=args.save_db, db_cfg=db_cfg)
        except Exception as e:
            import traceback
            print(f"실패 ({audio_path.name}): {e}")
            traceback.print_exc()

    remaining = list_pending(src)
    print(f"\n[완료] 처리 {len(targets)}개 | 남은 파일 {len(remaining)}개")


if __name__ == "__main__":
    main()
