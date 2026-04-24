# -*- coding: utf-8 -*-
"""Whisper 오인식 교정 패턴 및 교정 함수."""

COMPANY_NAME = "다올 비전"

COMPANY_VARIANTS = [
    "다월 기자", "다올 기자", "다홀 기자", "다홀 기장", "다올 기장", "자월 비잔",
    "다월 비전", "다홀 비전", "더울 비전",
    "다올 비젼", "다월 비젼", "다홀 비젼",
    "다올 기잠", "다월 기잠",
]

WHISPER_INITIAL_PROMPT = (
    f"{COMPANY_NAME} 상담원과 호텔 프런트 직원 간 PMS·키오스크 기술지원 통화. "
    "객실키, 체크인, 부킹엔진, 세팅값, 템플릿, 이메일 발송."
)

GENERAL_CORRECTIONS = [
    ("소스텔", "호스텔"),
    ("홈페이지 가산", "홈즈스테이 가산"),
    # PMS·키오스크 도메인 용어
    ("키 오스크", "키오스크"),
    ("기오스크", "키오스크"),
    ("키요스크", "키오스크"),
    ("체크 인", "체크인"),
    ("체크 아웃", "체크아웃"),
    ("부킹 엔진", "부킹엔진"),
    ("북킹엔진", "부킹엔진"),
    ("객실 키", "객실키"),
    ("세팅 값", "세팅값"),
    ("프런드", "프런트"),
    ("디스펜서", "디스펜서"),
    ("템플릿", "템플릿"),
    # 실제 STT 로그 보고 계속 추가
]


def fix_company_name(text: str) -> str:
    """STT 결과에서 회사명 오인식 변형 및 일반 오인식 패턴을 보정."""
    if not text:
        return text
    for v in COMPANY_VARIANTS:
        text = text.replace(v, COMPANY_NAME)
    for wrong, correct in GENERAL_CORRECTIONS:
        text = text.replace(wrong, correct)
    return text
