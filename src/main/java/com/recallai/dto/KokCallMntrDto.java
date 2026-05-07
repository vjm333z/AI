package com.recallai.dto;

import lombok.Data;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Data
public class KokCallMntrDto {
    private Integer seqNo;
    private String propCd;
    private String cmpxCd;
    private String title;
    private String report;
    private String feedback;
    private String feedbackYn;
    private String rDt;
    private String systemCd;
    private String systemNm;
    private String systemTpDtl;
    private String systemTpDtlNm;

    private static final String REPORT_BODY_MARKER  = "문의내역:";
    private static final String SUMMARY_DROP_MARKER = "통화요약:";
    private static final int    EMBEDDING_MAX_LEN   = 1000;

    /** 임베딩 입력 — 메타·AI요약 잘라낸 문의 본문, 1000자 캡. */
    public String toEmbeddingText() {
        String cleaned = cleanDisplay(report);

        // "문의내역:" 이전 메타 정보(접수번호 등) 제거 — 본문만
        int idx = cleaned.indexOf(REPORT_BODY_MARKER);
        if (idx >= 0) cleaned = cleaned.substring(idx + REPORT_BODY_MARKER.length()).trim();

        // "통화요약:" 이후는 AI 생성 요약 — Q-Q 매칭 취지에 맞지 않아 제외
        int summaryIdx = cleaned.indexOf(SUMMARY_DROP_MARKER);
        if (summaryIdx >= 0) cleaned = cleaned.substring(0, summaryIdx).trim();

        return cleaned.length() > EMBEDDING_MAX_LEN ? cleaned.substring(0, EMBEDDING_MAX_LEN) : cleaned;
    }

    private static final Pattern HTML_TAG       = Pattern.compile("<[^>]*>");
    private static final Pattern WHITESPACE_RUN = Pattern.compile("\\s+");
    private static final Pattern NUMERIC_ENTITY = Pattern.compile("&#(\\d+);");

    /** HTML 태그·엔티티 제거 + 공백 정규화. 적재·표시·임베딩 입력 공통 정제기. */
    public static String cleanDisplay(String raw) {
        if (raw == null) return "";
        String stripped = HTML_TAG.matcher(raw).replaceAll(" ")
                .replace("&nbsp;", " ")
                .replace("&amp;",  "&")
                .replace("&lt;",   "<")
                .replace("&gt;",   ">")
                .replace("&quot;", "\"")
                .replace("&apos;", "'");
        return WHITESPACE_RUN.matcher(unescapeNumericEntities(stripped)).replaceAll(" ").trim();
    }

    private static String unescapeNumericEntities(String s) {
        Matcher m = NUMERIC_ENTITY.matcher(s);
        StringBuffer sb = new StringBuffer();
        while (m.find()) {
            int codePoint = Integer.parseInt(m.group(1));
            m.appendReplacement(sb, Matcher.quoteReplacement(new String(Character.toChars(codePoint))));
        }
        m.appendTail(sb);
        return sb.toString();
    }
}
