#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""
hotels.json에 aliases를 자동 생성/추가하는 1회성 유틸리티.

실행:
    python patch_hotels_aliases.py
    python patch_hotels_aliases.py --hotels ../../hotels.json --dry-run

동작:
  - 이미 aliases가 있는 호텔은 건드리지 않음 (기존 수동 편집 보존)
  - 없는 경우에만 규칙 기반 + 하드코드 aliases 추가
  - --dry-run: 변경 내용만 출력하고 파일은 수정하지 않음
"""

import argparse
import json
import os
import re
import sys
import time
import urllib.request
from pathlib import Path

GROQ_URL = "https://api.groq.com/openai/v1/chat/completions"
GROQ_MODEL = "llama-3.3-70b-versatile"

LLM_SYSTEM_PROMPT = """너는 한국어 음성인식(Whisper) 오인식 전문가야.
호텔 이름을 입력받으면, Whisper가 잘못 인식할 수 있는 한국어/영어 변형만 생성해.

규칙:
1. 공백 삽입/제거 변형 (예: "해운대웨이브" → "해운대 웨이브")
2. 유사 발음 교체 (ㅅ↔ㅆ, ㅔ↔ㅐ, ㅗ↔ㅜ 등)
3. 영어 이름은 한국어 발음으로 (예: "ORAKAI" → "오라카이", "오락카이")
4. 법인명 접두어 제거 (주식회사, (주), ㈜ 등)
5. 음절 뒤바뀜, 탈락, 삽입 등 현실적인 오인식
6. 공백 위치 달리하기

절대 금지: 일본어(히라가나·가타카나), 중국어, 특수문자 사용 금지.
한국어와 영어(로마자)만 사용할 것.

JSON 배열로만 반환. 설명 없이. 원본 이름 제외. 최대 20개."""


# ────────────────────────────────────────────────────────────────
# 하드코드 aliases (propShrtNm 기준으로 매핑)
# Whisper가 자주 틀리는 이름, 약칭, 영어 발음 등을 수동으로 지정
# ────────────────────────────────────────────────────────────────
HARDCODED = {
    # ─── 표준 변형 (공백·법인명) + 심하게 틀린 Whisper 오인식 ───────────────
    "해마호텔":             ["해마 호텔", "헤마호텔", "헤마 호텔"],
    "제주푸른호텔":         ["제주 푸른 호텔", "제주 푸른호텔", "제주 푸린호텔"],
    "㈜호텔에이치엘비":     ["호텔에이치엘비", "호텔 에이치엘비", "HLB", "에이치엘비", "에이치 엘비"],
    "ORAKAI":              ["오라카이", "오라 카이", "오라카이 호텔", "오락카이", "오라케이", "오라 케이"],
    "유닛제이호스텔":       ["유닛 제이 호스텔", "유닛제이 호스텔", "유닛 제이"],
    "골드코스트호텔인천":   ["골드코스트 호텔", "골드 코스트 호텔", "골드코스트", "골드 코스트"],
    "유닛엠호스텔":         ["유닛 엠 호스텔", "유닛 엠"],
    "써미트스테이":         ["써미트 스테이", "서미트스테이", "서미트 스테이", "써미트",
                             "서밋 스테이", "서밋스테이", "씨미트"],
    "유닛디호스텔":         ["유닛 디 호스텔", "유닛 디"],
    "더 시에나":            ["시에나", "더시에나", "토스카나", "시에나 리조트",
                             "더 시에 나", "시에 나", "시에나호텔"],
    "클라우드웨스트호스피탈리티": ["클라우드웨스트", "클라우드 웨스트", "온심재",
                                   "클라우드 웨스트 호스피탈리티"],
    "강릉씨티호텔":         ["강릉 씨티 호텔", "강릉 시티 호텔", "강릉시티호텔",
                             "강릉 씨디 호텔", "강릉 시디 호텔"],
    "에이치엘비에프앤비 주식회사": ["에이치엘비에프앤비", "홍삼빌", "호텔 홍삼빌",
                                    "에이치 엘비 에프앤비"],
    "영동대학교":           ["강릉영동대학교", "영동대", "강릉영동대", "영동 대학교"],
    "주식회사 팔라티움":    ["팔라티움", "팔라티움 해운대", "팔라 티움", "팔라티움해운대"],
    "(주)제주호텔더엠":     ["제주호텔더엠", "더엠", "더 엠", "제주 더엠",
                             "제주 호텔 더 엠", "더엠호텔"],
    "스태이링크":           ["스테이링크", "스태이 링크", "스테이 링크",
                             "스테이 링그", "스태이링그"],
    "(주)빌텍":             ["모비딕", "모비딕 호텔", "모비 딕", "모비딬", "모비 딬"],
    "에스턴호텔춘천":       ["에스턴 호텔", "에스턴 춘천", "어스턴호텔", "에스턴호텔",
                             "이스턴 호텔", "이스턴호텔춘천"],
    "모멘토 호스텔":        ["모멘토호스텔", "모멘토", "모멘 토 호스텔", "모멘토 종로"],
    "비스타케이호텔월드컵": ["비스타케이", "비스타 케이", "비스타케이 호텔",
                             "비스타 케이 호텔", "비스타 케이 호텔 월드컵"],
    "호텔어라운드 속초":    ["어라운드 속초", "호텔어라운드", "어라운드속초",
                             "호텔 어라운드 속초", "어라운 속초"],
    "젠하이더웨이(트로피컬하이드어웨이)": ["젠하이더웨이", "하이드어웨이",
                                            "트로피컬 하이드어웨이", "트로피컬하이드어웨이",
                                            "하이드 어웨이", "젠 하이더웨이", "트로피컬"],
    "청수당스테이 북촌":    ["청수당", "청수당스테이", "청수 당", "청수당 스테이"],
    "서울도농상회":         ["도농상회", "도농 상회", "도농"],
    "해운대 웨이브":        ["해운대웨이브", "웨이브 해운대", "웨이브", "해운대 웨이 브"],
    "오션팰리스호텔":       ["오션팰리스", "오션 팰리스 호텔", "오션 팰리스",
                             "오션 팰리 스", "오션팰러스"],
    "Surestay Plus Asan Hotel": ["슈어스테이", "슈어 스테이", "아산 호텔",
                                  "베스트웨스턴 아산", "슈어스테이 아산", "슈어 스테이 아산"],
    "스테이파이 파주케이힐스": ["스테이파이", "파주케이힐스", "케이힐스",
                                "파주 케이힐스", "스테이 파이", "파주 케이 힐스"],
    "인트라다호텔 이천":    ["인트라다", "인트라다 이천", "인트라다호텔",
                             "인트라 다", "인트라 다 호텔"],
    "엠83호텔":             ["엠83", "엠 83", "M83", "엠 팔삼", "엠팔삼"],
    "주식회사 페텔":        ["코브스테이", "코브 스테이", "코브해운대", "코브 해운대"],
    "이호MH호텔":           ["이호 MH", "이호엠에이치", "MH호텔", "이호 엠에이치",
                             "이호 엠 에이치", "이호엠에이치호텔"],
    "재단법인 라이나전성기재단": ["바랑재", "라이나 바랑재", "라이나전성기재단"],
    "애월비치호텔":         ["애월비치", "애월 비치", "애월 비치 호텔",
                             "에월비치", "에월 비치"],
    "고성군 유스호스텔":    ["고성 유스호스텔", "고성유스호스텔", "고성 유스"],
    "에스앤호텔":           ["에스앤 호텔", "S&N 호텔", "에스 앤 호텔"],
    "엔조이 스테이":        ["엔조이스테이", "엔조이", "엔 조이 스테이"],
    "의료법인 성광의료재단": ["성광의료재단", "마티네차움", "마티네 차움",
                              "성광 의료재단", "마티 네 차움"],
    "보타닉호텔":           ["보타닉 호텔", "보타 닉 호텔"],
    "주식회사 미싱이야기":  ["메가스테이", "메가스테이 창신동", "메가 스테이"],
}


def _rule_aliases(name: str) -> list:
    """규칙 기반 자동 alias 생성 (하드코드 보완용)."""
    candidates = set()

    # 공백 제거 버전
    no_space = name.replace(" ", "")
    if no_space != name and len(no_space) > 1:
        candidates.add(no_space)

    # 공백 추가 버전 (붙어있는 한글 2글자 단위로)
    # 너무 공격적이라 skip — 하드코드로 관리

    # ㅆ→ㅅ, ㅅ→ㅆ 교체 (Whisper 자주 혼동)
    subs = [
        ("써", "서"), ("씨", "시"), ("쌍", "상"), ("쎄", "세"),
        ("스태이", "스테이"), ("스테이", "스태이"),
    ]
    for src, dst in subs:
        if src in name:
            candidates.add(name.replace(src, dst))
        if dst in name:
            candidates.add(name.replace(dst, src))

    # 법인 접두어 제거 (주식회사, (주), ㈜, 재단법인 등)
    for prefix in ["주식회사 ", "(주)", "㈜", "재단법인 ", "의료법인 "]:
        if name.startswith(prefix):
            stripped = name[len(prefix):]
            if stripped:
                candidates.add(stripped)

    # 원본 제거
    candidates.discard(name)
    return sorted(candidates)


def _llm_aliases(name: str, api_key: str, existing: list) -> list:
    """Groq로 Whisper 오인식 변형 생성. 실패 시 빈 리스트."""
    if not api_key:
        return []
    body = json.dumps({
        "model": GROQ_MODEL,
        "messages": [
            {"role": "system", "content": LLM_SYSTEM_PROMPT},
            {"role": "user", "content": f"호텔 이름: {name}"},
        ],
        "max_tokens": 300,
        "temperature": 0.7,
        "response_format": {"type": "json_object"},
    }).encode("utf-8")

    # JSON object 반환이라 배열을 감싸도록 프롬프트 보완
    body = json.dumps({
        "model": GROQ_MODEL,
        "messages": [
            {"role": "system", "content": LLM_SYSTEM_PROMPT},
            {"role": "user", "content": f'호텔 이름: {name}\n응답 형식: {{"aliases": ["변형1", "변형2", ...]}}'},
        ],
        "max_tokens": 300,
        "temperature": 0.7,
        "response_format": {"type": "json_object"},
    }).encode("utf-8")

    for attempt in range(4):
        try:
            req = urllib.request.Request(
                GROQ_URL, data=body, method="POST",
                headers={"Authorization": f"Bearer {api_key}",
                         "Content-Type": "application/json",
                         "User-Agent": "Mozilla/5.0"},
            )
            with urllib.request.urlopen(req, timeout=20) as r:
                res = json.loads(r.read().decode("utf-8"))
            content = json.loads(res["choices"][0]["message"]["content"])
            candidates = content.get("aliases", [])
            existing_set = set(existing) | {name}
            # 일본어(히라가나 3040-309F, 가타카나 30A0-30FF) 포함 항목 제거
            return [c for c in candidates
                    if isinstance(c, str) and c and c not in existing_set
                    and not re.search(r'[\u3040-\u30FF\u4E00-\u9FFF]', c)]
        except urllib.error.HTTPError as e:
            if e.code == 429:
                wait = 15 * (attempt + 1)
                print(f"  [LLM] 429 rate limit — {wait}초 대기 후 재시도...")
                time.sleep(wait)
            else:
                print(f"  [LLM] {name} HTTP {e.code} 실패")
                return []
        except Exception as e:
            print(f"  [LLM] {name} 실패: {e}")
            return []
    return []


def generate_aliases(hotel: dict, api_key: str = None, target: int = 20) -> list:
    """호텔 1개에 대한 aliases 생성. 하드코드 → 규칙 → LLM 순으로 target 개수까지 채움."""
    name = hotel.get("propShrtNm", "")
    full = hotel.get("propFullNm", "")

    result = list(HARDCODED.get(name, []))

    # 하드코드 없으면 규칙 기반으로 보완
    if not result:
        result = _rule_aliases(name)

    # propFullNm이 다르면 추가 (법인명이 아닐 때만)
    legal_prefixes = ["주식회사", "(주)", "㈜", "재단법인", "의료법인"]
    if full and full.strip() != name and not any(full.startswith(p) for p in legal_prefixes):
        clean_full = full.strip()
        if clean_full not in result and clean_full != name:
            result.append(clean_full)

    # cmpxNm이 propShrtNm과 다르면 추가 (단일 complex인 경우)
    complexes = hotel.get("complexes", [])
    if len(complexes) == 1:
        cmpx_nm = complexes[0].get("cmpxNm", "").strip()
        if cmpx_nm and cmpx_nm != name and cmpx_nm not in result:
            result.append(cmpx_nm)

    # LLM으로 target 개수까지 보완
    if api_key and len(result) < target:
        llm = _llm_aliases(name, api_key, result)
        result.extend(llm[:target - len(result)])
        time.sleep(2.0)  # TPM 한도 완화 (12kTPM / ~300토큰 = 40호텔/분 → 1.5s 이상 필요)

    return result


def main():
    parser = argparse.ArgumentParser(description="hotels.json에 aliases 자동 추가")
    parser.add_argument("--hotels", default=None, help="hotels.json 경로")
    parser.add_argument("--dry-run", action="store_true", help="출력만, 파일 수정 없음")
    parser.add_argument("--overwrite", action="store_true",
                        help="기존 aliases가 있어도 덮어씀 (기본: 비어있는 것만 채움)")
    parser.add_argument("--llm", action="store_true",
                        help="Groq LLM으로 aliases 20개까지 자동 보완 (GROQ_API_KEY 필요)")
    parser.add_argument("--target", type=int, default=20,
                        help="호텔당 목표 alias 수 (기본 20, --llm 사용 시 적용)")
    args = parser.parse_args()

    api_key = os.environ.get("GROQ_API_KEY") if args.llm else None
    if args.llm and not api_key:
        sys.exit("❌ --llm 사용 시 GROQ_API_KEY 환경변수 필요")

    hotels_path = args.hotels
    if not hotels_path:
        auto = Path(__file__).parent.parent / "data" / "hotels.json"
        if auto.exists():
            hotels_path = str(auto)
    if not hotels_path or not Path(hotels_path).exists():
        sys.exit(f"❌ hotels.json 없음: {hotels_path}")

    with open(hotels_path, encoding="utf-8") as f:
        hotels = json.load(f)

    def save(note=""):
        if not args.dry_run and changed > 0:
            with open(hotels_path, "w", encoding="utf-8") as f:
                json.dump(hotels, f, ensure_ascii=False, indent=2)
            print(f"✅ hotels.json 저장 완료{note}: {hotels_path}")

    changed = 0
    try:
        for h in hotels:
            existing = h.get("aliases", [])
            if existing and not args.overwrite:
                if not args.llm or len(existing) >= args.target:
                    continue
            new_aliases = generate_aliases(h, api_key=api_key, target=args.target)
            if new_aliases != existing:
                print(f"  {h.get('propShrtNm')} ({len(existing)}→{len(new_aliases)}개) → {new_aliases}")
                h["aliases"] = new_aliases
                changed += 1
    except KeyboardInterrupt:
        print(f"\n⚠️ 중단됨 — 지금까지 변경 {changed}개 저장 중...")
        save(" (중단 시점)")
        sys.exit(0)

    print(f"\n{'[DRY-RUN] ' if args.dry_run else ''}변경 대상: {changed}개 호텔")
    save()
    if args.dry_run:
        print("(dry-run 모드 — 파일 미수정)")


if __name__ == "__main__":
    main()
