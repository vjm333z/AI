package com.recallai.model;

import java.util.Locale;

/**
 * RAG 검색 컬렉션 모드.
 *
 * <p>DEFAULT: 원본 REPORT 임베딩 컬렉션 (inquiry).
 * TEMPLATED: HyDE 템플릿 컬렉션 (inquiry_templated).
 *
 * <p>외부 입력(API mode 파라미터)는 소문자 문자열이므로 {@link #fromString(String)}으로 매핑.
 */
public enum SearchMode {
    DEFAULT,
    TEMPLATED;

    /** API mode 파라미터(예: "templated") → enum 매핑. null/미지원 값은 DEFAULT 폴백. */
    public static SearchMode fromString(String mode) {
        if (mode == null || mode.isBlank()) return DEFAULT;
        try {
            return SearchMode.valueOf(mode.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return DEFAULT;
        }
    }
}
