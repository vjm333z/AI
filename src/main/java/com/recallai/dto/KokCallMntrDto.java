package com.recallai.dto;

import lombok.Data;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Data
public class KokCallMntrDto {
    private Integer seqNo;
    private String propCd;
    private String title;
    private String report;
    private String raction;
    private String feedback;
    private String feedbackYn;
    private String rDt;
    private String cmpxCd;
    private String systemCd;
    private String systemNm;
    private String systemTpDtl;
    private String systemTpDtlNm;

    public String toEmbeddingText() {
        String cleaned = cleanDisplay(report);

        // "문의내역:" 이전 메타 정보(접수번호 등) 제거 — 문의 본문만 임베딩
        int idx = cleaned.indexOf("문의내역:");
        if (idx >= 0) cleaned = cleaned.substring(idx + 5).trim();

        // "통화요약:" 이후는 AI 생성 요약이라 원본 문의 의도와 다를 수 있어 제외
        // Q-Q 매칭 취지(문의끼리 비교)에도 맞지 않음
        // TODO: 실제 DB 데이터에서 패턴 확인 후 필요 없으면 제거
        int summaryIdx = cleaned.indexOf("통화요약:");
        if (summaryIdx >= 0) cleaned = cleaned.substring(0, summaryIdx).trim();

        return cleaned.length() > 1000 ? cleaned.substring(0, 1000) : cleaned;
    }

    public static String cleanDisplay(String raw) {
        if (raw == null) return "";
        return unescapeNumericEntities(raw
                .replaceAll("<[^>]*>", " ")
                .replace("&nbsp;", " ")
                .replace("&amp;", "&")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&quot;", "\"")
                .replace("&apos;", "'"))
                .replaceAll("\\s+", " ")
                .trim();
    }

    private static final Pattern NUMERIC_ENTITY = Pattern.compile("&#(\\d+);");

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
