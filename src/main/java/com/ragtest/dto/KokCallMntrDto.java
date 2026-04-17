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

    // 임베딩용 텍스트 조합
    public String toEmbeddingText() {
        StringBuilder sb = new StringBuilder();
        if (report != null) {
            String cleanReport = report
                    .replaceAll("<[^>]*>", " ")  // HTML 태그 제거
                    .replaceAll("\\s+", " ")      // 연속 공백 제거
                    .trim();
            sb.append("문의내용: ").append(cleanReport).append(" ");
        }
        if (feedback != null) {
            String cleanFeedback = feedback.trim();
            sb.append("답변: ").append(cleanFeedback);
        }
        return sb.toString().trim();
    }
}
