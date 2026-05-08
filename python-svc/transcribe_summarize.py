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
import requests as _requests
from pathlib import Path

from corrections import fix_company_name, WHISPER_INITIAL_PROMPT, DAOL_RECEIVER_NOS
from hotel_matcher import (
    load_hotels, build_alias_pairs, fix_hotel_names,
    find_hotel_by_call_no,
    find_hotel_from_text, find_cmpx_from_text, add_alias,
)
from db_utils import DB_DEFAULT, save_to_aia

# .env 파일 자동 로드 (환경변수 없을 때 폴백)
_env_file = Path(__file__).parent / ".env"
if _env_file.exists():
    for _line in _env_file.read_text(encoding="utf-8").splitlines():
        _line = _line.strip()
        if _line and not _line.startswith("#") and "=" in _line:
            _k, _v = _line.split("=", 1)
            os.environ[_k.strip()] = _v.strip()

GROQ_STT_API_KEY = os.environ.get("GROQ_STT_API_KEY") or os.environ.get("GROQ_API_KEY")
GROQ_LLM_API_KEY = os.environ.get("GROQ_LLM_API_KEY") or os.environ.get("GROQ_API_KEY")

OPENAI_API_KEY  = os.environ.get("OPENAI_API_KEY", "")
LLM_PROVIDER    = os.environ.get("LLM_PROVIDER", "openai")  # openai | groq

GROQ_URL     = "https://api.groq.com/openai/v1/chat/completions"
GROQ_STT_URL = "https://api.groq.com/openai/v1/audio/transcriptions"
GROQ_MODEL     = "llama-3.3-70b-versatile"
GROQ_STT_MODEL = "whisper-large-v3"

OPENAI_URL   = "https://api.openai.com/v1/chat/completions"
OPENAI_MODEL = os.environ.get("OPENAI_MODEL", "gpt-4o-mini")


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

def transcribe_groq(audio_path: str) -> dict:
    """Groq Whisper API로 STT — CPU 부하 없음, 5분 통화 → 약 10초."""
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
  "report": "⭐ KOK_CALL_MNTR.REPORT에 저장될 값. 아래 형식을 정확히 따를 것 (줄바꿈 포함):\n문의자 : [발신자의 담당자명 또는 부서명. 호텔명은 절대 쓰지 말 것 (호텔은 별도 관리). 예: '프런트 담당자', '예약실', '김철수 대리'. 파악 안 되면 '미확인']\n연락처: [통화에서 언급된 전화번호. 없으면 '미확인']\n문의내역: [발신자 문의 내용을 사실 위주 2~4줄 서술. 상황·증상·요청 포함. 상담원 답변 내용 섞지 마. 호실번호·예약번호 등 구체 정보 그대로 포함]",
  "feedback": "⭐ 상담원이 제공한 답변·조치·후속안내를 통합해 KOK_CALL_MNTR.FEEDBACK 컬럼에 바로 넣을 수 있는 서술형 2~4줄. 예: '세팅값 확인 결과 일부 설정 누락. 수정 후 재전송 안내. 추후 공지사항으로 안내 예정.'",
  "system_cd": "접수시스템 코드. 아래 중 하나만 출력(코드만): PMS(프런트·예약·정산·객실 등 PMS 관련) / KIOSK(키오스크 장비·오류·사용법) / POS(POS 단말·결제) / CMS(CMS 관련) / DBE(부킹엔진·OTA) / ETC(기타)",
  "system_con": "접수내용 코드. system_cd 먼저 판단한 뒤 아래 표에서 가장 잘 맞는 코드 하나만 출력(숫자만).\n[PMS] 1(예약) 2(프런트) 3(정산) 4(객실정비) 5(일마감) 8(업장관리) 9(메뉴관리) 10(예약관리) 27(정보관리) 28(상품/판매관리) 29(예약확인) 49(사용처리관련) 50(매출관련) 51(객실관리관련)\n[KIOSK] 24(에러/조회) 26(사용법)\n[DBE] 25(OTA예약)\n[ETC] 26(사용법)",
  "system_tp": "접수유형 코드. 아래 중 하나만 출력(코드만): 01(신규기능 요청) / 02(시스템장애·오류) / 03(단순문의·사용법) / 04(칭찬) / 05(불만)",
  "urgency_cd": "긴급도 코드. 아래 중 하나만 출력(알파벳만): A(심각: 전체 사용불가·데이터손실·즉시처리필요) / B(중요: 일부기능불가·업무지연) / C(보통: 불편하지만업무가능·일반장애) / D(낮음: 단순문의·설정변경·사용법질문)",
  "status": "해결됨 / 처리중 / 추가조사 필요 / 미확인 중 하나",
  "category": "키오스크 / 객실키 / 결제·카드 / 체크인 / PMS기능 / 부킹엔진 / 소모품·시재 / 기타 중 하나",
  "question": "발신자의 핵심 문의 (50자 이내, 의문문)",
  "context": "질문 배경·상황 (1-2줄)",
  "answer_given": "상담원이 제공한 답변·조치 (없으면 '답변 보류')",
  "actions_taken": ["실제 취해진 조치 리스트"],
  "follow_up": "이후 처리 예정 사항 (없으면 '없음')",
  "hotel_nm": "통화에서 언급된 발신 호텔·업장명. STT 텍스트나 화자 발화에서 들린 그대로. 예: '홈즈스테이', '가산 그래비티', '수원 노보텔'. 전혀 알 수 없으면 null",
  "caller_nm": "문의자명+직함. 통화에서 파악된 이름·직책 조합. 예: '김정자 지배인', '박철수 대리', '예약실 담당자'. 이름도 직함도 전혀 모를 때만 '미확인'",
  "speaker_A": "역할 추정 (예: '다올 비전 상담원 (수신측)')",
  "speaker_B": "역할 추정 (예: '홈즈스테이 예약실 직원 (발신측)')",
  "inquirer": "A or B",
  "responder": "A or B",
  "summary": "통화 전체를 상세히 서술. 발신자 문의 배경·증상·요청 → 상담원 확인·조치 → 결론·후속사항 순서로 5~10줄 자세한 한국어 서술형 요약. 핵심 조치와 미결 사항은 반드시 포함.",
  "dialogue": [
    {"speaker": "A", "start": 0.0, "text": "정리된 발화"},
    {"speaker": "B", "start": 3.5, "text": "..."}
  ]
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
1. **dialogue는 마지막 필드**. 대화 흐름을 보여주는 핵심 턴 5~15개로 축약. 입력 세그먼트가 50개든 100개든 전부 담지 마. 긴 발화는 1~2줄로 요약해서 화자별 핵심 의도만 남기고, 단순 맞장구("네네")는 생략.
2. 화자 판별 단서:
   - 전화 건 쪽 = 인사말 뒤 곧바로 용건 꺼냄 ("홈즈스테이 예약실인데요", "저희가 뭐 여쭤볼 게 있어서")
   - 받은 쪽 = 회사명 짧게 밝힘 ("다월입니다"), 질문 주도 ("어떤 상품일까요?")
3. 호실번호, 전화번호, 예약번호, 이름 등 통화에서 언급된 구체 정보는 **그대로** 기록해. 지우거나 마스킹하지 마. 호텔 브랜드명은 역할 추정용 한 번만 사용 후 이후엔 '해당 업장' 등으로 일반화 가능.
4. STT 인식 오류 가능성 — 문맥으로 판단해 자연스럽게 정리. 단, 없는 내용은 지어내지 마.
5. 불명확한 부분은 "미확인"으로.
6. **system_cd, system_con, system_tp, urgency_cd, question, answer_given, summary 등 모든 분석 필드는 반드시 채워**. "(미확인)"은 정말 파악 안 될 때만 사용.
7. question / answer_given 은 각각 **inquirer / responder 발화에 근거**해 작성."""


def _inject_caller_contact(report: str, caller_no: str, receiver_no: str = None) -> str:
    """다올 번호를 제외한 상대방 번호를 연락처에 주입."""
    if not report:
        return report
    if caller_no and caller_no not in DAOL_RECEIVER_NOS:
        contact = caller_no
    elif receiver_no and receiver_no not in DAOL_RECEIVER_NOS:
        contact = receiver_no
    else:
        contact = caller_no or receiver_no
    if not contact:
        return report
    return re.sub(r"연락처\s*:[ \t]*.*", f"연락처: {contact}", report)


def _call_openai_llm(user_input: str, timeout: int = 60) -> dict:
    """OpenAI gpt-4o-mini로 STT 요약 JSON 반환. 실패 시 예외 발생."""
    if not OPENAI_API_KEY:
        raise RuntimeError("OPENAI_API_KEY 없음")

    body = {
        "model": OPENAI_MODEL,
        "messages": [
            {"role": "system", "content": SUMMARIZE_SYSTEM_PROMPT},
            {"role": "user",   "content": user_input},
        ],
        "max_tokens": 4000,
        "temperature": 0.2,
        "response_format": {"type": "json_object"},
    }

    for attempt in range(4):
        resp = _requests.post(
            OPENAI_URL,
            headers={"Authorization": f"Bearer {OPENAI_API_KEY}"},
            json=body,
            timeout=timeout,
        )
        if resp.status_code == 429:
            m = re.search(r"try again in ([\d.]+)s", resp.text)
            wait = float(m.group(1)) + 2.0 if m else 60.0
            print(f"[요약-OpenAI] Rate limit (429) — {wait:.1f}초 후 재시도 ({attempt+1}/4)")
            time.sleep(wait)
            continue
        if not resp.ok:
            raise RuntimeError(f"OpenAI HTTP {resp.status_code}: {resp.text[:300]}")
        break
    else:
        raise RuntimeError("OpenAI rate limit 재시도 초과")

    content = resp.json()["choices"][0]["message"]["content"]
    return json.loads(content)


def summarize(segments: list, context: dict = None, timeout: int = 60) -> dict:
    """STT 세그먼트 → 화자 분리 대화록 + 구조화 요약 JSON."""
    if not segments:
        return {"error": "빈 세그먼트"}

    lines = []
    for seg in segments:
        start = seg.get("start", 0)
        mmss = f"{int(start // 60):d}:{int(start % 60):02d}"
        lines.append(f"[{mmss}] {seg.get('text', '').strip()}")
    stt_text = "\n".join(lines)

    if context:
        meta = ["[통화 메타정보 — 아래 값은 시스템이 확정한 값이므로 report 필드에 그대로 사용할 것]"]
        if context.get("hotel_name"):
            meta.append(f"- 발신 호텔명 (참고용): {context['hotel_name']}  ← speaker_B 역할 파악에만 사용. report의 '문의자'에는 호텔명 쓰지 말고 담당자명·부서명으로 채울 것")
        if context.get("caller_no"):
            meta.append(f"- 연락처(발신번호): {context['caller_no']}  ← report의 '연락처' 줄에 이 값 그대로 사용")
        if context.get("receiver_no"):
            is_daol = context.get("receiver_no") in (context.get("daol_nos") or set())
            meta.append(f"- 수신번호: {context['receiver_no']} ({'다올 비전 측' if is_daol else '상대측'})")
        if context.get("daol_nos"):
            meta.append(f"- 다올 비전 수신번호 목록: {', '.join(sorted(context['daol_nos']))}  ← 이 번호들 = 다올 비전 상담원(수신측)")
        if context.get("call_dt"):
            meta.append(f"- 통화일시: {context['call_dt']}")
        user_input = "\n".join(meta) + "\n\n" + stt_text
    else:
        user_input = stt_text

    print(f"[요약] 호출 (세그먼트 {len(segments)}개, 입력 {len(user_input)}자, provider={LLM_PROVIDER})...")
    t0 = time.time()

    # OpenAI 우선 시도
    if LLM_PROVIDER == "openai" and OPENAI_API_KEY:
        try:
            result = _call_openai_llm(user_input, timeout=timeout)
            print(f"[요약] OpenAI 완료 ({time.time()-t0:.1f}초)")
            return result
        except Exception as e:
            print(f"[요약] OpenAI 실패, Groq 폴백: {e}")

    # Groq 폴백
    if not GROQ_LLM_API_KEY:
        return {"error": "GROQ_LLM_API_KEY 없음 — OpenAI도 실패했거나 설정 안 됨"}

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

    for attempt in range(4):
        try:
            resp = _requests.post(
                GROQ_URL,
                headers={"Authorization": f"Bearer {GROQ_LLM_API_KEY}"},
                json=body,
                timeout=timeout,
            )
            if resp.status_code == 429:
                m = re.search(r"try again in ([\d.]+)s", resp.text)
                wait = float(m.group(1)) + 2.0 if m else 60.0
                print(f"[요약-Groq] Rate limit (429) — {wait:.1f}초 후 재시도 (시도 {attempt+1}/4)")
                time.sleep(wait)
                continue
            if not resp.ok:
                return {"error": f"Groq HTTP {resp.status_code}: {resp.text[:500]}"}
            res = resp.json()
            break
        except Exception as e:
            return {"error": f"{type(e).__name__}: {e}"}
    else:
        return {"error": "Groq rate limit 재시도 초과"}

    print(f"[요약] Groq 완료 ({time.time()-t0:.1f}초)")
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
# 메인 파이프라인 (단독 CLI 실행용)
# ────────────────────────────────────────────────────────────────

def main():
    # Windows 한글 콘솔(cp949)에서 유니코드 출력 깨짐 방지 — CLI 실행 시에만
    if sys.platform == "win32":
        try:
            sys.stdout = io.TextIOWrapper(sys.stdout.buffer, encoding="utf-8", errors="replace")
            sys.stderr = io.TextIOWrapper(sys.stderr.buffer, encoding="utf-8", errors="replace")
        except (AttributeError, ValueError):
            pass

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
                        help="처리 완료 후 AIA_CALL_* 테이블에 저장")
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

    # Step 2 — 요약 (발/수신번호로 알 수 있는 컨텍스트 미리 전달)
    summary = None
    if not args.no_summary:
        pre_hotel, pre_cmpx = None, None
        if hotels:
            pre_hotel, pre_cmpx = find_hotel_by_call_no(caller_no, hotels)
            if not pre_hotel:
                pre_hotel, pre_cmpx = find_hotel_by_call_no(receiver_no, hotels)
        hotel_display = (pre_cmpx.get("cmpxNm") if pre_cmpx else None) or (pre_hotel.get("propShrtNm") if pre_hotel else None)
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
    else:
        print("[요약] 스킵 (--no-summary)")

    # Step 2.5 — 호텔 매칭 (발/수신번호 → 텍스트)
    # 매칭 결과는 응답 JSON에만 포함. AIA 테이블에는 저장하지 않음 (화면에서 매핑).
    prop_cd, cmpx_cd = None, None
    if hotels:
        matched_hotel, matched_cmpx = None, None

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

        if not matched_hotel and summary and "error" not in summary:
            search_text = " ".join(filter(None, [
                summary.get("speaker_B", ""),
                summary.get("report", ""),
                stt_result.get("text", "")[:500],
            ]))
            matched_hotel, alias_candidate = find_hotel_from_text(search_text, hotels)
            if matched_hotel:
                prop_cd     = matched_hotel.get("propCd")
                matched_cmpx = find_cmpx_from_text(search_text, matched_hotel)
                if matched_cmpx:
                    cmpx_cd = matched_cmpx.get("cmpxCd")
                print(f"[호텔] 텍스트 매칭: {matched_hotel.get('propShrtNm')} "
                      f"(prop_cd={prop_cd}, cmpx_cd={cmpx_cd})")
                if alias_candidate and hotels_path:
                    add_alias(hotels_path, prop_cd, alias_candidate)

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
        rec_seq_no = save_to_aia(result, db_cfg)
        if rec_seq_no:
            result["rec_seq_no"] = rec_seq_no
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


if __name__ == "__main__":
    main()
