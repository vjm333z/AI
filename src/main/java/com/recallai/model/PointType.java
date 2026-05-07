package com.recallai.model;

/**
 * Qdrant payload의 type 필드 값.
 *
 * <p>FAQ: 사전 정의된 FAQ 항목 (faq.json 적재).
 * REAL: KOK_CALL_MNTR 실제 상담 사례.
 */
public enum PointType {
    FAQ,
    REAL;

    /** Qdrant payload에 저장할 문자열 값. enum 이름을 그대로 사용. */
    public String value() {
        return name();
    }
}
