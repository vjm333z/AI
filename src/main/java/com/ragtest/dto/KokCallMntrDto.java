package com.ragtest.dto;

public class KokCallMntrDto {
    private Integer seqNo;
    private String propCd;
    private String title;
    private String report;
    private String raction;
    private String feedback;
    private String feedbackYn;
    private String rDt;

    public Integer getSeqNo() { return seqNo; }
    public void setSeqNo(Integer seqNo) { this.seqNo = seqNo; }

    public String getPropCd() { return propCd; }
    public void setPropCd(String propCd) { this.propCd = propCd; }

    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }

    public String getReport() { return report; }
    public void setReport(String report) { this.report = report; }

    public String getRaction() { return raction; }
    public void setRaction(String raction) { this.raction = raction; }

    public String getFeedback() { return feedback; }
    public void setFeedback(String feedback) { this.feedback = feedback; }

    public String getFeedbackYn() { return feedbackYn; }
    public void setFeedbackYn(String feedbackYn) { this.feedbackYn = feedbackYn; }

    public String getRDt() { return rDt; }
    public void setRDt(String rDt) { this.rDt = rDt; }

    /**
     * 임베딩용 텍스트 (Q-Q 매칭).
     * TITLE + REPORT를 정제해서 합친다. FEEDBACK은 제외.
     */
    public String toEmbeddingText() {
        StringBuilder sb = new StringBuilder();
        String t = cleanTruncate(title, 100);
        String r = cleanTruncate(report, 1000);
        if (!t.isEmpty()) sb.append(t).append(" ");
        if (!r.isEmpty()) sb.append(r);
        return sb.toString().trim();
    }

    /** 저장·표시용 정제 (길이 제한 없음) — HTML 태그 + 엔티티 + 연속 공백 정리 */
    public static String cleanDisplay(String raw) {
        if (raw == null) return "";
        return raw
                .replaceAll("<[^>]*>", " ")
                .replace("&nbsp;", " ")
                .replace("&amp;", "&")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&quot;", "\"")
                .replace("&#39;", "'")
                .replace("&apos;", "'")
                .replaceAll("&#\\d+;", "")    // 기타 숫자 엔티티 제거
                .replaceAll("\\s+", " ")
                .trim();
    }

    /** 임베딩용 정제 — cleanDisplay + 길이 제한 */
    private static String cleanTruncate(String raw, int maxLen) {
        String s = cleanDisplay(raw);
        return s.length() > maxLen ? s.substring(0, maxLen) : s;
    }
}
