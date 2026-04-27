#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""
배치 처리 스크립트 — 폴더의 mp3 파일을 STT 서버로 일괄 전송.

사용법:
    python batch_process.py                     # 기본: inbox/ 폴더
    python batch_process.py C:/recordings       # 특정 폴더
    python batch_process.py C:/recordings --force  # 이미 처리된 것도 재처리
"""

import sys
import time
import argparse
from pathlib import Path

import requests

SERVER = "http://localhost:8001"


def upload(file_path: Path, force: bool = False) -> dict:
    url = f"{SERVER}/api/recording" + ("?force=true" if force else "")
    with open(file_path, "rb") as f:
        resp = requests.post(url, files={"file": (file_path.name, f, "audio/mpeg")}, timeout=10)
    return {"status": resp.status_code, "body": resp.json()}


def main():
    parser = argparse.ArgumentParser(description="STT 배치 처리")
    parser.add_argument("folder", nargs="?", default="inbox", help="처리할 mp3 폴더 (기본: inbox/)")
    parser.add_argument("--force", action="store_true", help="이미 처리된 파일도 재처리")
    parser.add_argument("--ext", default="mp3", help="파일 확장자 (기본: mp3)")
    args = parser.parse_args()

    folder = Path(args.folder)
    if not folder.exists():
        print(f"[오류] 폴더 없음: {folder}")
        sys.exit(1)

    files = sorted(folder.glob(f"*.{args.ext}"))
    if not files:
        print(f"[알림] {folder} 에 .{args.ext} 파일이 없습니다.")
        sys.exit(0)

    print(f"[배치] {len(files)}개 파일 처리 시작 (force={args.force})")
    print(f"       서버: {SERVER}\n")

    ok = skip = fail = 0
    for i, fp in enumerate(files, 1):
        print(f"[{i}/{len(files)}] {fp.name} ... ", end="", flush=True)
        try:
            result = upload(fp, args.force)
            sc = result["status"]
            body = result["body"]
            if sc == 202:
                print(f"수신 완료")
                ok += 1
            elif sc == 409:
                print(f"스킵 (이미 처리됨) — {body.get('message','')}")
                skip += 1
            else:
                print(f"실패 [{sc}] {body}")
                fail += 1
        except requests.exceptions.ConnectionError:
            print(f"[오류] 서버 연결 실패 — python -m uvicorn server:app --port 8001 실행 필요")
            sys.exit(1)
        except Exception as e:
            print(f"[오류] {e}")
            fail += 1

        # 서버 부하 분산: 파일 간 0.5초 간격
        if i < len(files):
            time.sleep(0.5)

    print(f"\n[완료] 전송 {ok}건 / 스킵 {skip}건 / 실패 {fail}건")
    print(f"       결과는 results/ 폴더와 CALL_QUEUE DB에서 확인 가능합니다.")
    if ok:
        print(f"       STT 처리는 백그라운드에서 진행 중 — 잠시 후 api/admin/recent 로 확인하세요.")


if __name__ == "__main__":
    main()
