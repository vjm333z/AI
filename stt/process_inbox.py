#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""
inbox/ 에 쌓인 미처리 mp3 파일을 직접 STT 파이프라인으로 처리.
서버 API 없이 단독 실행 가능.

사용법:
    python process_inbox.py           # inbox/ 전체 처리
    python process_inbox.py --dry-run # 파일 목록만 확인
"""

import sys
import argparse
from pathlib import Path

BASE_DIR = Path(__file__).parent
sys.path.insert(0, str(BASE_DIR))

from server import process_recording, INBOX_DIR


def main():
    parser = argparse.ArgumentParser(description="inbox 미처리 파일 일괄 STT 처리")
    parser.add_argument("--dry-run", action="store_true", help="실제 처리 없이 목록만 출력")
    args = parser.parse_args()

    files = sorted(INBOX_DIR.glob("*.mp3"))
    if not files:
        print(f"[알림] inbox/ 에 처리할 파일이 없습니다.")
        return

    print(f"[처리 대상] {len(files)}개 파일\n")
    for i, fp in enumerate(files, 1):
        print(f"  [{i}] {fp.name}")

    if args.dry_run:
        print("\n--dry-run 모드: 실제 처리하지 않음")
        return

    print(f"\n처리를 시작합니다...\n")
    for i, fp in enumerate(files, 1):
        print(f"[{i}/{len(files)}] {fp.name}")
        try:
            process_recording(fp)
        except Exception as e:
            print(f"  -> 실패: {e}")

    print(f"\n[완료] 결과는 results/ 폴더와 CALL_QUEUE DB에서 확인하세요.")


if __name__ == "__main__":
    main()
