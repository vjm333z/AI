package com.recallai.util;

import java.util.regex.Pattern;

/**
 * LLM 컨텍스트·로그용 가벼운 텍스트 정제기.
 * 적재 시점 정제(HTML 엔티티 포함)는 {@link com.recallai.dto.KokCallMntrDto#cleanDisplay}를 쓴다.
 */
public final class TextSanitizer {

    private static final Pattern HTML_TAG       = Pattern.compile("<[^>]*>");
    private static final Pattern WHITESPACE_RUN = Pattern.compile("\\s+");

    private TextSanitizer() {}

    /**
     * HTML 태그 제거 + 연속 공백 1칸으로. {@code null} 또는 문자열 "null" 은 빈 문자열로.
     * {@code Object} 를 받아 호출 측 {@code String.valueOf(...)} 부담을 줄임.
     */
    public static String cleanForPrompt(Object raw) {
        if (raw == null) return "";
        String text = raw.toString();
        if (text.isEmpty() || "null".equals(text)) return "";
        String stripped = HTML_TAG.matcher(text).replaceAll(" ");
        return WHITESPACE_RUN.matcher(stripped).replaceAll(" ").trim();
    }
}
