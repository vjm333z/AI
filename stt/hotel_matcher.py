# -*- coding: utf-8 -*-
"""호텔 매칭 로직: hotels.json 로드, alias 보정, 전화번호/텍스트 기반 매칭."""

import json
import re


def load_hotels(json_path: str) -> list:
    try:
        with open(json_path, encoding="utf-8") as f:
            return json.load(f)
    except Exception as e:
        print(f"[호텔] hotels.json 로드 실패 ({json_path}): {e}")
        return []


def load_phone_lookup(json_path: str) -> dict:
    """phone_lookup.json 로드. {digits: prop_cd} 형태. 없으면 빈 dict."""
    try:
        with open(json_path, encoding="utf-8") as f:
            return json.load(f)
    except Exception:
        return {}


def build_alias_pairs(hotels: list) -> list:
    """alias → hotel 매핑 리스트 (긴 alias 우선 — 부분치환 오버랩 방지)."""
    pairs = []
    for h in hotels:
        for alias in h.get("aliases", []):
            if alias:
                pairs.append((alias, h))
    pairs.sort(key=lambda x: len(x[0]), reverse=True)
    return pairs


def fix_hotel_names(text: str, alias_pairs: list) -> str:
    """STT 텍스트의 호텔명 오인식을 정식명으로 치환."""
    if not text or not alias_pairs:
        return text
    for alias, hotel in alias_pairs:
        text = text.replace(alias, hotel.get("propShrtNm", alias))
    return text


def find_hotel_from_text(text: str, hotels: list) -> dict:
    """텍스트에서 호텔 매칭: 정확 → 부분 포함 → fuzzy (threshold 0.65)."""
    if not text or not hotels:
        return None

    from difflib import SequenceMatcher

    for h in hotels:
        for name in [h.get("propShrtNm", ""), h.get("propFullNm", "")]:
            if name and name in text:
                return h

    for h in hotels:
        for alias in h.get("aliases", []):
            if alias and alias in text:
                return h

    best, best_score = None, 0.65
    for h in hotels:
        name = h.get("propShrtNm", "")
        if not name:
            continue
        win_len = len(name) + 4
        for i in range(max(1, len(text) - win_len + 1)):
            window = text[i:i + win_len]
            score = SequenceMatcher(None, name, window).ratio()
            if score > best_score:
                best_score = score
                best = h
    return best


def find_hotel_by_call_no(receiver_no: str, hotels: list) -> tuple:
    """수신번호로 호텔/컴플렉스 매칭 (cmpxReprTel + mobileNos).
    반환: (matched_hotel, matched_cmpx) or (None, None).
    """
    if not receiver_no or not hotels:
        return None, None
    normalized = re.sub(r'\D', '', receiver_no)
    if not normalized:
        return None, None
    for h in hotels:
        for c in h.get('complexes', []):
            repr_tel = re.sub(r'\D', '', c.get('cmpxReprTel', '') or '')
            if repr_tel and repr_tel == normalized:
                return h, c
            for mobile in c.get('mobileNos', []):
                if re.sub(r'\D', '', mobile) == normalized:
                    return h, c
    return None, None


def find_hotel_by_phone_lookup(phone_no: str, lookup: dict, hotels: list):
    """phone_lookup에서 전화번호 → prop_cd 조회 후 hotel 객체 반환."""
    if not phone_no or not lookup or not hotels:
        return None
    digits = re.sub(r'\D', '', phone_no)
    prop_cd = lookup.get(digits)
    if not prop_cd:
        return None
    return next((h for h in hotels if h.get("propCd") == prop_cd), None)


def find_cmpx_from_text(text: str, hotel: dict) -> dict:
    """호텔 complexes 중 텍스트에 언급된 것 반환 (긴 이름 우선)."""
    if not text or not hotel:
        return None
    complexes = sorted(
        hotel.get("complexes", []),
        key=lambda c: len(c.get("cmpxNm", "")),
        reverse=True,
    )
    for c in complexes:
        nm = c.get("cmpxNm", "")
        if nm and nm in text:
            return c
    return None
