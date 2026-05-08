#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""
Whisper STT — Groq API 우선, 로컬 faster-whisper 폴백.

파이프라인 오케스트레이션(요약·호텔매칭·DB 저장·파일이동)은 모두 Spring에서 수행.
이 모듈은 stt_router.py의 /stt 엔드포인트가 호출하는 순수 STT 함수만 보유.
"""

import os
import re
import sys
import time
from pathlib import Path

import requests as _requests

from corrections import fix_company_name, WHISPER_INITIAL_PROMPT

# .env 파일 자동 로드 (환경변수 없을 때 폴백)
_env_file = Path(__file__).parent / ".env"
if _env_file.exists():
    for _line in _env_file.read_text(encoding="utf-8").splitlines():
        _line = _line.strip()
        if _line and not _line.startswith("#") and "=" in _line:
            _k, _v = _line.split("=", 1)
            os.environ[_k.strip()] = _v.strip()

GROQ_STT_API_KEY = os.environ.get("GROQ_STT_API_KEY") or os.environ.get("GROQ_API_KEY")
GROQ_STT_URL     = "https://api.groq.com/openai/v1/audio/transcriptions"
GROQ_STT_MODEL   = "whisper-large-v3"


def transcribe_groq(audio_path: str) -> dict:
    """Groq Whisper API로 STT — CPU 부하 없음, 5분 통화 → 약 10초.

    응답 JSON 구조:
        {
          "text": "...",
          "segments": [{"start": 0.0, "end": 1.5, "text": "..."}, ...],
          "duration_sec": 120.0,
          "language": "ko",
          "language_probability": 1.0
        }
    """
    if not GROQ_STT_API_KEY:
        raise RuntimeError("GROQ_STT_API_KEY 없음 — .env 또는 환경변수에 추가 필요")

    print(f"[STT-Groq] 전송 중: {audio_path}")
    t0 = time.time()

    with open(audio_path, "rb") as f:
        file_data = f.read()

    fields = {
        "model": GROQ_STT_MODEL,
        "language": "ko",
        "response_format": "verbose_json",
        "prompt": WHISPER_INITIAL_PROMPT,
    }
    for attempt in range(5):
        resp = _requests.post(
            GROQ_STT_URL,
            headers={"Authorization": f"Bearer {GROQ_STT_API_KEY}"},
            files={"file": (os.path.basename(audio_path), file_data, "audio/mpeg")},
            data=fields,
            timeout=120,
        )
        if resp.status_code == 429:
            wait = 60
            try:
                m = re.search(r"try again in ([0-9.]+)s", resp.text)
                if m:
                    wait = int(float(m.group(1))) + 2
            except Exception:
                pass
            print(f"[STT-Groq] 429 rate limit, {wait}초 대기 후 재시도 ({attempt+1}/5)")
            time.sleep(wait)
            continue
        break
    if not resp.ok:
        raise RuntimeError(f"Groq STT 실패 HTTP {resp.status_code}: {resp.text[:300]}")
    res = resp.json()

    elapsed  = time.time() - t0
    duration = res.get("duration", 0)

    segments, full_text_parts = [], []
    for seg in res.get("segments", []):
        # Whisper 도메인 오인식 보정 (회사명·PMS 용어 등) 적용 후 반환
        corrected = fix_company_name(seg.get("text", "").strip())
        segments.append({"start": round(seg.get("start", 0), 2),
                         "end":   round(seg.get("end",   0), 2),
                         "text":  corrected})
        full_text_parts.append(corrected)

    print(f"[STT-Groq] 완료: 음성 {duration:.1f}초 → 처리 {elapsed:.1f}초 · {len(segments)}개 세그먼트",
          flush=True)
    return {
        "text": " ".join(full_text_parts),
        "segments": segments,
        "duration_sec": duration,
        "language": res.get("language", "ko"),
        "language_probability": 1.0,
    }


# ────────────────────────────────────────────────────────────────
# CLI 단독 실행 — 디버깅용 ("python transcribe_summarize.py xxx.mp3")
# ────────────────────────────────────────────────────────────────
if __name__ == "__main__":
    if len(sys.argv) < 2:
        sys.exit("사용법: python transcribe_summarize.py <audio.mp3>")
    result = transcribe_groq(sys.argv[1])
    print()
    print("=" * 60)
    print(f"전사 텍스트 ({result['duration_sec']:.1f}초, {len(result['segments'])}개 세그먼트)")
    print("=" * 60)
    print(result["text"])
