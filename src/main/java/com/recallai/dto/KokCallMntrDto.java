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
        int idx = cleaned.indexOf("문의내역:");
        if (idx >= 0) cleaned = cleaned.substring(idx + 5).trim();
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
