#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""
호텔 PMS 상담 통화 녹취 (mp3) → STT → 요약 JSON

사용법:
    python transcribe_summarize.py recording.mp3
    python transcribe_summarize.py recording.mp3 --model small
    python transcribe_summarize.py recording.mp3 --output result.json --no-summary

의존성:
    pip install -r requirements.txt
    (별도로 ffmpeg 설치 필요 — mp3 디코딩용)

처음 실행 시 Whisper 모델(medium = 1.5GB) 자동 다운로드됩니다.
"""

import argparse
import io
import json
import os
import re
import sys
import time
import urllib.request
from pathlib import Path

from corrections import fix_company_name, WHISPER_INITIAL_PROMPT
from hotel_matcher import (
    load_hotels, load_phone_lookup, build_alias_pairs, fix_hotel_names,
    find_hotel_by_call_no, find_hotel_by_phone_lookup,
    find_hotel_from_text, find_cmpx_from_text,
)
from db_utils import DB_DEFAULT, lookup_caller_history, save_to_db

# Windows 한글 콘솔(cp949)에서 유니코드 출력 깨짐 방지
if sys.platform == "win32":
    try:
        sys.stdout = io.TextIOWrapper(sys.stdout.buffer, encoding="utf-8", errors="replace")
        sys.stderr = io.TextIOWrapper(sys.stderr.buffer, encoding="utf-8", errors="replace")
    except (AttributeError, ValueError):
        pass

GROQ_API_KEY = os.environ.get("GROQ_API_KEY")
if not GROQ_API_KEY:
    sys.exit("❌ GROQ_API_KEY 환경변수가 필요합니다. export GROQ_API_KEY=gsk_...")

GROQ_URL     = "https://api.groq.com/openai/v1/chat/completions"
GROQ_STT_URL = "https://api.groq.com/openai/v1/audio/transcriptions"
GROQ_MODEL     = "llama-3.3-70b-versatile"
GROQ_STT_MODEL = "whisper-large-v3"


# ────────────────────────────────────────────────────────────────
# 파일명 파싱
# ────────────────────────────────────────────────────────────────

def parse_filename_meta(audio_path: str) -> dict:
    """{발신}-{수신}-{YYYYMMDDHHMMSS}-{index} 형식 파일명에서 메타 파싱."""
    stem = Path(audio_path).stem
    parts = stem.split("-")
    if len(parts) < 3:
        return {"caller_no": None, "receiver_no": None, "call_dt": None}

    dt_idx = None
    for i, p in enumerate(parts):
        if re.fullmatch(r"\d{14}", p):
            dt_idx = i
            break
    if dt_idx is None or dt_idx < 2:
        return {"caller_no": None, "receiver_no": None, "call_dt": None}

    caller_no   = parts[0]
    receiver_no = "-".join(parts[1:dt_idx])
    dt_str      = parts[dt_idx]
    m = re.fullmatch(r"(\d{4})(\d{2})(\d{2})(\d{2})(\d{2})(\d{2})", dt_str)
    call_dt = f"{m.group(1)}-{m.group(2)}-{m.group(3)} {m.group(4)}:{m.group(5)}:{m.group(6)}" if m else None

    return {"caller_no": caller_no, "receiver_no": receiver_no, "call_dt": call_dt}


# ────────────────────────────────────────────────────────────────
# STT
# ────────────────────────────────────────────────────────────────

def _build_multipart(fields: dict, filename: str, file_data: bytes) -> tuple:
    import uuid
    boundary = uuid.uuid4().hex
    body = b""
    for name, value in fields.items():
        body += (f"--{boundary}\r\n"
                 f'Content-Disposition: form-data; name="{name}"\r\n\r\n'
                 f"{value}\r\n").encode("utf-8")
    body += (f"--{boundary}\r\n"
             f'Content-Disposition: form-data; name="file"; filename="{filename}"\r\n'
             f"Content-Type: audio/mpeg\r\n\r\n").encode("utf-8")
    body += file_data + b"\r\n"
    body += f"--{boundary}--\r\n".encode("utf-8")
    return body, f"multipart/form-data; boundary={boundary}"


def transcribe_groq(audio_path: str) -> dict:
    """Groq Whisper API로 STT — CPU 부하 없음, 5분 통화 → 약 10초."""
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
    body, content_type = _build_multipart(fields, os.path.basename(audio_path), file_data)

    req = urllib.request.Request(
        GROQ_STT_URL, method="POST", data=body,
        headers={
            "Authorization": f"Bearer {GROQ_API_KEY}",
            "Content-Type": content_type,
            "User-Agent": "Mozilla/5.0",
        },
    )
    try:
        with urllib.request.urlopen(req, timeout=120) as r:
            res = json.loads(r.read().decode("utf-8"))
    except urllib.error.HTTPError as e:
        raise RuntimeError(f"Groq STT 실패 HTTP {e.code}: {e.read().decode()[:300]}")

    elapsed  = time.time() - t0
    duration = res.get("duration", 0)

    segments, full_text_parts = [], []
    for seg in res.get("segments", []):
        corrected = fix_company_name(seg.get("text", "").strip())
        segments.append({"start": round(seg.get("start", 0), 2),
                         "end":   round(seg.get("end",   0), 2),
                         "text":  corrected})
        full_text_parts.append(corrected)

    print(f"[STT-Groq] 완료: 음성 {duration:.1f}초 → 처리 {elapsed:.1f}초 · {len(segments)}개 세그먼트")
    return {
        "text": " ".join(full_text_parts),
        "segments": segments,
        "duration_sec": duration,
        "language": res.get("language", "ko"),
        "language_probability": 1.0,
    }


def transcribe(audio_path: str, model_size: str = "medium",
               device: str = "cpu", compute_type: str = "int8") -> dict:
    """로컬 faster-whisper STT."""
    try:
        from faster_whisper import WhisperModel
    except ImportError:
        sys.exit("❌ faster-whisper 미설치: pip install -r requirements.txt")

    print(f"[STT] 모델 로드 중... (size={model_size}, device={device})")
    t0 = time.time()
    model = WhisperModel(model_size, device=device, compute_type=compute_type)
    print(f"[STT] 모델 로드 완료 ({time.time()-t0:.1f}초)")

    print(f"[STT] 전사 시작: {audio_path}")
    t0 = time.time()
    segments, info = model.transcribe(
        audio_path, language="ko", beam_size=5,
        vad_filter=True,
        vad_parameters=dict(min_silence_duration_ms=500),
        initial_prompt=WHISPER_INITIAL_PROMPT,
    )

    seg_list, full_text_parts = [], []
    for seg in segments:
        corrected = fix_company_name(seg.text.strip())
        seg_list.append({"start": round(seg.start, 2),
                         "end":   round(seg.end,   2),
                         "text":  corrected})
        full_text_parts.append(corrected)

    elapsed  = time.time() - t0
    duration = info.duration
    print(f"[STT] 완료: 음성 {duration:.1f}초 → 처리 {elapsed:.1f}초 · {len(seg_list)}개 세그먼트")
    return {
        "text": " ".join(full_text_parts),
        "segments": seg_list,
        "duration_sec": duration,
        "language": info.language,
        "language_probability": round(info.language_probability, 3),
    }


# ────────────────────────────────────────────────────────────────
# 요약
# ────────────────────────────────────────────────────────────────

SUMMARIZE_SYSTEM_PROMPT = """너는 호텔 PMS 상담 통화 모노 녹취록을 분석하는 전문가야.

[중요 컨텍스트]
- **"다올 비전"** 이 자사(상담 제공자) 이름이야. STT가 이걸 "다월 기자", "다홀 기자", "다월 비전" 등으로 잘못 인식했을 수 있는데, 그런 표현이 보이면 모두 **다올 비전**으로 간주해.
- 다올 비전 상담원 = 호텔 PMS·키오스크 기술지원 담당 (수신측).
- 호텔 프런트 직원 = 장애·설정 문의로 전화한 쪽 (발신측).

모노 녹음이라 발신자/수신자가 하나의 오디오에 섞여 있어.
대화 맥락(인사말, 문의 시작, 높임말 톤, 질문-답변 패턴)을 보고 **A/B 두 화자로 추정 분리**하고 구조화된 분석을 만들어.

[입력]
타임스탬프가 붙은 STT 전사 세그먼트들.

[출력 — 순수 JSON. 설명·마크다운 금지]
{
  "report": "⭐ KOK_CALL_MNTR.REPORT에 저장될 값. 아래 형식을 정확히 따를 것 (줄바꿈 포함):\n문의자 : [발신자의 담당자명 또는 부서명. 호텔명은 절대 쓰지 말 것 (호텔은 별도 관리). 예: '프런트 담당자', '예약실', '김철수 대리'. 파악 안 되면 '미확인']\n연락처: [통화에서 언급된 전화번호. 없으면 '미확인'. 개인 핸드폰은 010-****-NNNN 형태로 마스킹]\n문의내역: [발신자 문의 내용을 사실 위주 2~4줄 서술. 상황·증상·요청 포함. 상담원 답변 내용 섞지 마. 호실번호·예약번호 등 PII 제거]",
  "feedback": "⭐ 상담원이 제공한 답변·조치·후속안내를 통합해 KOK_CALL_MNTR.FEEDBACK 컬럼에 바로 넣을 수 있는 서술형 2~4줄. 예: '세팅값 확인 결과 일부 설정 누락. 수정 후 재전송 안내. 추후 공지사항으로 안내 예정.'",
  "speaker_A": "역할 추정 (예: '다올 비전 상담원 (수신측)')",
  "speaker_B": "역할 추정 (예: '홈즈스테이 예약실 직원 (발신측)')",
  "dialogue": [
    {"speaker": "A", "start": 0.0, "text": "정리된 발화"},
    {"speaker": "B", "start": 3.5, "text": "..."}
  ],
  "inquirer": "A" or "B",
  "responder": "A" or "B",
  "question": "발신자의 핵심 문의 (50자 이내, 의문문)",
  "context": "질문 배경·상황 (1-2줄)",
  "answer_given": "상담원이 제공한 답변·조치 (없으면 '답변 보류')",
  "actions_taken": ["실제 취해진 조치 리스트"],
  "follow_up": "이후 처리 예정 사항 (없으면 '없음')",
  "status": "해결됨 / 처리중 / 추가조사 필요 / 미확인 중 하나",
  "category": "키오스크 / 객실키 / 결제·카드 / 체크인 / PMS기능 / 부킹엔진 / 소모품·시재 / 기타 중 하나",
  "summary": "3줄 이내 한국어 요약"
}

[report·feedback 필드 지침]
- **report**: 반드시 아래 3줄 형식 유지. DB에 그대로 저장됨.
  ```
  문의자 : 홈즈스테이 예약실
  연락처: 미확인
  문의내역: 부킹엔진 판매 페이지에 신규 패키지 상품 설명란 미노출. 수원·가산 지점만 해당.
  ```
- **feedback**: 상담원이 실제로 말한 조치·답변·안내만. 문의자 발화 섞지 마. 상담원 답변이 없거나 보류면 '답변 보류 — 확인 후 재연락 예정' 식으로.
- 두 필드만 있어도 **원본 상담 기록을 재구성**할 수 있어야 함 (나머지 필드는 분석·검색용).

[규칙]
1. **dialogue는 대화 흐름을 보여주는 핵심 턴 5~15개로 축약**. 입력 세그먼트가 50개든 100개든 전부 담지 마. 긴 발화는 1~2줄로 요약해서 화자별 핵심 의도만 남기고, 단순 맞장구("네네")는 생략.
   ⚠️ **dialogue가 너무 길면 뒤 분석 필드(question/summary 등) 토큰 부족해서 잘림 — 반드시 축약.**
2. 화자 판별 단서:
   - 전화 건 쪽 = 인사말 뒤 곧바로 용건 꺼냄 ("홈즈스테이 예약실인데요", "저희가 뭐 여쭤볼 게 있어서")
   - 받은 쪽 = 회사명 짧게 밝힘 ("다월입니다"), 질문 주도 ("어떤 상품일까요?")
3. **PII 제거 또는 일반화**: 구체 호실번호(410호 같은), 전화번호, 예약번호, 사람 이름. 호텔 브랜드명은 역할 추정용 한 번만 사용 후 이후엔 '해당 업장' 등으로 일반화 가능.
4. STT 인식 오류 가능성 — 문맥으로 판단해 자연스럽게 정리. 단, 없는 내용은 지어내지 마.
5. 불명확한 부분은 "미확인"으로.
6. **question, answer_given, summary, category, status 등 모든 분석 필드는 반드시 채워**. "(미확인)"은 정말 파악 안 될 때만 사용.
7. question / answer_given 은 각각 **inquirer / responder 발화에 근거**해 작성."""


def summarize(segments: list, timeout: int = 60) -> dict:
    """STT 세그먼트 → 화자 분리 대화록 + 구조화 요약 JSON."""
    if not segments:
        return {"error": "빈 세그먼트"}

    lines = []
    for seg in segments:
        start = seg.get("start", 0)
        mmss = f"{int(start // 60):d}:{int(start % 60):02d}"
        lines.append(f"[{mmss}] {seg.get('text', '').strip()}")
    user_input = "\n".join(lines)

    body = {
        "model": GROQ_MODEL,
        "messages": [
            {"role": "system", "content": SUMMARIZE_SYSTEM_PROMPT},
            {"role": "user",   "content": user_input},
        ],
        "max_tokens": 4000,
        "temperature": 0.2,
        "response_format": {"type": "json_object"},
    }

    req = urllib.request.Request(
        GROQ_URL, method="POST",
        data=json.dumps(body).encode("utf-8"),
        headers={
            "Authorization": f"Bearer {GROQ_API_KEY}",
            "Content-Type": "application/json",
            "User-Agent": "Mozilla/5.0",
        },
    )

    print(f"[요약] Groq 호출 (세그먼트 {len(segments)}개, 입력 {len(user_input)}자)...")
    t0 = time.time()
    try:
        with urllib.request.urlopen(req, timeout=timeout) as r:
            res = json.loads(r.read().decode("utf-8"))
    except urllib.error.HTTPError as e:
        return {"error": f"HTTP {e.code}: {e.read().decode('utf-8', errors='ignore')[:500]}"}
    except Exception as e:
        return {"error": f"{type(e).__name__}: {e}"}

    print(f"[요약] 완료 ({time.time()-t0:.1f}초)")
    content = res["choices"][0]["message"]["content"]
    try:
        return json.loads(content)
    except json.JSONDecodeError:
        return {"error": "JSON 파싱 실패", "raw": content}


# ────────────────────────────────────────────────────────────────
# 마크다운 리포트
# ────────────────────────────────────────────────────────────────

def save_markdown_report(md_path: str, audio_filename: str,
                         stt: dict, summary: dict | None) -> None:
    import datetime

    def _show(v, empty_mark="_(비어있음)_"):
        if v is None: return empty_mark
        if isinstance(v, str) and not v.strip(): return empty_mark
        if isinstance(v, list) and not v: return empty_mark
        return v

    L = []
    L.append("# 통화 분석 리포트")
    L.append("")
    L.append(f"- **파일**: `{audio_filename}`")
    L.append(f"- **처리 일시**: {datetime.datetime.now().strftime('%Y-%m-%d %H:%M:%S')}")
    duration = stt.get("duration_sec", 0)
    L.append(f"- **음성 길이**: {duration:.1f}초 ({int(duration // 60)}분 {int(duration % 60)}초)")
    L.append(f"- **언어 인식**: {stt.get('language', 'ko')} "
             f"(신뢰도 {stt.get('language_probability', 0) * 100:.1f}%)")
    L.append("")

    if summary and "error" not in summary:
        L.append("---")
        L.append("## 📋 문의 내용 (REPORT)")
        L.append(f"> {_show(summary.get('report'))}")
        L.append("")
        L.append("## 💬 피드백 (FEEDBACK)")
        L.append(f"> {_show(summary.get('feedback'))}")
        L.append("")
        L.append("---")
        L.append("> 아래는 상세 분석 — 품질 검증용")
        L.append("")

        L.append("---")
        L.append("## 🎭 화자 추정")
        L.append(f"- **speaker_A**: {_show(summary.get('speaker_A'))}")
        L.append(f"- **speaker_B**: {_show(summary.get('speaker_B'))}")
        L.append(f"- **inquirer**: {_show(summary.get('inquirer'))}")
        L.append(f"- **responder**: {_show(summary.get('responder'))}")
        L.append("")

        L.append("---")
        L.append("## ❓ 핵심 문의")
        L.append(f"> {_show(summary.get('question'))}")
        L.append(f"\n**context**: {_show(summary.get('context'))}")
        L.append("")

        L.append("---")
        L.append("## 💡 응대")
        L.append(f"> {_show(summary.get('answer_given'))}")
        L.append("")
        actions = summary.get("actions_taken") or []
        L.append("**actions_taken**:")
        for a in actions:
            L.append(f"- {a}")
        if not actions:
            L.append("- _(비어있음)_")
        L.append(f"\n**follow_up**: {_show(summary.get('follow_up'))}")
        L.append("")
        L.append(f"**status**: `{_show(summary.get('status'))}` · "
                 f"**category**: `{_show(summary.get('category'))}`")
        L.append("")

        L.append("---")
        L.append("## 📝 한 줄 요약")
        L.append(f"{_show(summary.get('summary'))}")
        L.append("")

        dialogue = summary.get("dialogue") or []
        L.append("---")
        L.append(f"## 💬 화자 분리 대화록 ({len(dialogue)}줄)")
        L.append("")
        if dialogue:
            L.append("| 시각 | 화자 | 발화 |")
            L.append("|------|------|------|")
            for d in dialogue:
                start = d.get("start", 0)
                mmss  = f"{int(start // 60):d}:{int(start % 60):02d}"
                spk   = d.get("speaker", "?")
                text  = str(d.get("text", "")).replace("|", "\\|").replace("\n", " ")
                L.append(f"| {mmss} | **{spk}** | {text} |")
        else:
            L.append("_(비어있음)_")
        L.append("")

        L.append("---")
        L.append("## 📦 Raw JSON")
        L.append("<details><summary>펼쳐 보기</summary>")
        L.append("")
        L.append("```json")
        L.append(json.dumps(summary, ensure_ascii=False, indent=2))
        L.append("```")
        L.append("</details>")
        L.append("")
    elif summary and "error" in summary:
        L.append("---")
        L.append(f"## ⚠️ 요약 실패\n```\n{summary.get('error', '')}\n```")
        if "raw" in summary:
            L.append(f"### LLM 원문\n```\n{summary['raw']}\n```")

    L.append("---")
    L.append("## 📜 원본 전사")
    L.append("<details><summary>펼쳐 보기</summary>")
    L.append("")
    L.append(f"```\n{stt.get('text', '')}\n```")
    L.append("</details>")

    with open(md_path, "w", encoding="utf-8") as f:
        f.write("\n".join(L))


# ────────────────────────────────────────────────────────────────
# 메인 파이프라인
# ────────────────────────────────────────────────────────────────

def main():
    parser = argparse.ArgumentParser(
        description="호텔 통화 녹음(.mp3) → STT → 요약",
        formatter_class=argparse.RawDescriptionHelpFormatter,
        epilog=__doc__,
    )
    parser.add_argument("audio", help="입력 음성 파일 (.mp3 / .wav / .m4a)")
    parser.add_argument("--stt-provider", default="groq", choices=["groq", "local"])
    parser.add_argument("--model", default="medium",
                        choices=["tiny", "base", "small", "medium", "large-v3"])
    parser.add_argument("--device", default="cpu", choices=["cpu", "cuda"])
    parser.add_argument("--compute-type", default="int8",
                        choices=["int8", "float16", "float32"])
    parser.add_argument("--output", help="결과 JSON 저장 경로 (생략 시 results/<audio>.json)")
    parser.add_argument("--no-summary", action="store_true")
    parser.add_argument("--hotels", default=None,
                        help="hotels.json 경로. 생략 시 ../data/hotels.json 자동 탐색.")
    parser.add_argument("--save-db", action="store_true",
                        help="처리 완료 후 로컬 MariaDB(CALL_QUEUE)에 저장")
    parser.add_argument("--db-host",     default=DB_DEFAULT["host"])
    parser.add_argument("--db-port",     default=DB_DEFAULT["port"], type=int)
    parser.add_argument("--db-user",     default=DB_DEFAULT["user"])
    parser.add_argument("--db-password", default=DB_DEFAULT["password"])
    parser.add_argument("--db-name",     default=DB_DEFAULT["database"])
    args = parser.parse_args()

    db_cfg = {
        "host": args.db_host, "port": args.db_port,
        "user": args.db_user, "password": args.db_password,
        "database": args.db_name,
    } if args.save_db else None

    audio_path = args.audio
    if not os.path.exists(audio_path):
        sys.exit(f"❌ 파일 없음: {audio_path}")

    file_meta   = parse_filename_meta(audio_path)
    caller_no   = file_meta["caller_no"]
    receiver_no = file_meta["receiver_no"]
    call_dt     = file_meta["call_dt"]
    print(f"[메타] caller_no={caller_no}, receiver_no={receiver_no}, call_dt={call_dt}")

    # hotels.json 로드
    hotels_path = args.hotels
    if not hotels_path:
        auto = Path(__file__).parent.parent / "data" / "hotels.json"
        if auto.exists():
            hotels_path = str(auto)
    hotels      = load_hotels(hotels_path) if hotels_path else []
    alias_pairs = build_alias_pairs(hotels)
    if hotels:
        print(f"[호텔] {len(hotels)}개 호텔 로드 완료 "
              f"(aliases {sum(len(h.get('aliases', [])) for h in hotels)}개)")

    # phone_lookup.json 로드
    phone_lookup = {}
    if hotels_path:
        lookup_path  = str(Path(hotels_path).parent / "phone_lookup.json")
        phone_lookup = load_phone_lookup(lookup_path)
        if phone_lookup:
            print(f"[전화] phone_lookup 로드 완료: {len(phone_lookup)}개 번호")

    # 결과 저장 경로
    if args.output:
        output_path = args.output
    else:
        results_dir = Path(__file__).parent / "results"
        results_dir.mkdir(exist_ok=True)
        output_path = str(results_dir / (Path(audio_path).stem + ".json"))
    Path(output_path).parent.mkdir(parents=True, exist_ok=True)

    # Step 1 — STT
    if args.stt_provider == "groq":
        stt_result = transcribe_groq(audio_path)
    else:
        stt_result = transcribe(audio_path, model_size=args.model,
                                device=args.device, compute_type=args.compute_type)

    # Step 1.5 — 호텔명 오인식 보정
    if alias_pairs:
        stt_result["text"] = fix_hotel_names(stt_result["text"], alias_pairs)
        for seg in stt_result.get("segments", []):
            seg["text"] = fix_hotel_names(seg["text"], alias_pairs)
        print("[호텔] STT 텍스트 호텔명 보정 완료")

    # Step 2 — 요약
    summary = None
    if not args.no_summary:
        summary = summarize(stt_result["segments"])
    else:
        print("[요약] 스킵 (--no-summary)")

    # Step 2.5 — 호텔 매칭 (0순위: 수신번호 → 1순위: phone_lookup → 2순위: 히스토리 → 3순위: 텍스트)
    prop_cd, cmpx_cd = None, None
    if hotels:
        matched_hotel, matched_cmpx = None, None

        # 발신번호 우선 시도 (호텔 → 다올 비전 인바운드가 일반적), 실패 시 수신번호
        matched_hotel, matched_cmpx = find_hotel_by_call_no(caller_no, hotels)
        if matched_hotel:
            prop_cd  = matched_hotel.get("propCd")
            cmpx_cd  = matched_cmpx.get("cmpxCd") if matched_cmpx else None
            print(f"[호텔] 발신번호 매칭: {matched_hotel.get('propShrtNm')} "
                  f"(prop_cd={prop_cd}, cmpx_cd={cmpx_cd})")
        if not matched_hotel:
            matched_hotel, matched_cmpx = find_hotel_by_call_no(receiver_no, hotels)
            if matched_hotel:
                prop_cd  = matched_hotel.get("propCd")
                cmpx_cd  = matched_cmpx.get("cmpxCd") if matched_cmpx else None
                print(f"[호텔] 수신번호 매칭: {matched_hotel.get('propShrtNm')} "
                      f"(prop_cd={prop_cd}, cmpx_cd={cmpx_cd})")

        if not matched_hotel and phone_lookup:
            matched_hotel = find_hotel_by_phone_lookup(caller_no, phone_lookup, hotels)
            if matched_hotel:
                prop_cd = matched_hotel.get("propCd")
                print(f"[호텔] phone_lookup 매칭(발신): {matched_hotel.get('propShrtNm')} (prop_cd={prop_cd})")

        if not matched_hotel and phone_lookup and receiver_no:
            matched_hotel = find_hotel_by_phone_lookup(receiver_no, phone_lookup, hotels)
            if matched_hotel:
                prop_cd = matched_hotel.get("propCd")
                print(f"[호텔] phone_lookup 매칭(수신): {matched_hotel.get('propShrtNm')} (prop_cd={prop_cd})")

        if not matched_hotel and caller_no and args.save_db:
            hist_prop, hist_cmpx = lookup_caller_history(caller_no, db_cfg)
            if hist_prop:
                prop_cd       = hist_prop
                cmpx_cd       = hist_cmpx
                matched_hotel = next((h for h in hotels if h.get("propCd") == prop_cd), None)
                print(f"[호텔] 발신번호 히스토리 매칭: prop_cd={prop_cd}, cmpx_cd={cmpx_cd}")

        if not matched_hotel and summary and "error" not in summary:
            search_text = " ".join(filter(None, [
                summary.get("speaker_B", ""),
                summary.get("report", ""),
                stt_result.get("text", "")[:500],
            ]))
            matched_hotel = find_hotel_from_text(search_text, hotels)
            if matched_hotel:
                prop_cd     = matched_hotel.get("propCd")
                matched_cmpx = find_cmpx_from_text(search_text, matched_hotel)
                if matched_cmpx:
                    cmpx_cd = matched_cmpx.get("cmpxCd")
                print(f"[호텔] 텍스트 매칭: {matched_hotel.get('propShrtNm')} "
                      f"(prop_cd={prop_cd}, cmpx_cd={cmpx_cd})")

        if matched_hotel and cmpx_cd is None:
            complexes = matched_hotel.get("complexes", [])
            if len(complexes) == 1:
                cmpx_cd = complexes[0].get("cmpxCd")
                print(f"[호텔] 단일 complex 자동 채움: cmpx_cd={cmpx_cd}")

        if not matched_hotel:
            print("[호텔] 매칭 실패 — prop_cd=null (상담사가 UI에서 선택 필요)")

    result = {
        "audio_file":  os.path.basename(audio_path),
        "caller_no":   caller_no,
        "receiver_no": receiver_no,
        "call_dt":     call_dt,
        "prop_cd":     prop_cd,
        "cmpx_cd":     cmpx_cd,
        "stt":         stt_result,
        "summary":     summary,
    }

    with open(output_path, "w", encoding="utf-8") as f:
        json.dump(result, f, ensure_ascii=False, indent=2)
    print(f"\n✅ JSON 저장: {output_path}")

    if args.save_db:
        call_id = save_to_db(result, db_cfg)
        if call_id:
            result["call_id"] = call_id
            with open(output_path, "w", encoding="utf-8") as f:
                json.dump(result, f, ensure_ascii=False, indent=2)

    md_path = str(Path(output_path).with_suffix(".md"))
    save_markdown_report(md_path, os.path.basename(audio_path), stt_result, summary)
    print(f"✅ MD 리포트: {md_path}")

    print("\n" + "=" * 60)
    print("📝 전사 텍스트 (처음 500자):")
    print("=" * 60)
    print(stt_result["text"][:500] + ("..." if len(stt_result["text"]) > 500 else ""))

    if summary and "error" not in summary:
        print("\n" + "=" * 60)
        print("📋 문의 내용 (REPORT):")
        print("=" * 60)
        print(f"  {summary.get('report', '(비어있음)')}")
        print("\n" + "=" * 60)
        print("💬 피드백 (FEEDBACK):")
        print("=" * 60)
        print(f"  {summary.get('feedback', '(비어있음)')}")

        print("\n" + "=" * 60)
        print("🎭 화자 추정:")
        print("=" * 60)
        for k in ["speaker_A", "speaker_B", "inquirer", "responder"]:
            if k in summary:
                print(f"  {k}: {summary[k]}")

        dialogue = summary.get("dialogue", [])
        if dialogue:
            print("\n" + "=" * 60)
            print(f"💬 화자 분리 대화록 ({len(dialogue)}줄):")
            print("=" * 60)
            for d in dialogue[:20]:
                spk   = d.get("speaker", "?")
                start = d.get("start", 0)
                mmss  = f"{int(start // 60):d}:{int(start % 60):02d}"
                print(f"  [{mmss}] {spk}: {d.get('text', '')}")
            if len(dialogue) > 20:
                print(f"  ... (전체 {len(dialogue)}줄, JSON 파일 참조)")

        print("\n" + "=" * 60)
        print("📋 구조화 분석:")
        print("=" * 60)
        for k in ["question", "context", "answer_given", "status", "category", "summary"]:
            if k in summary:
                print(f"  {k}: {summary[k]}")
        if summary.get("actions_taken"):
            print("  actions_taken:")
            for item in summary["actions_taken"]:
                print(f"    - {item}")
        if summary.get("follow_up") and summary["follow_up"] != "없음":
            print(f"  follow_up: {summary['follow_up']}")
    elif summary and "error" in summary:
        print(f"\n⚠️ 요약 실패: {summary['error']}")
        if "raw" in summary:
            print(f"   원문 일부: {summary['raw'][:200]}")


if __name__ == "__main__":
    main()
