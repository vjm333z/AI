package com.recallai.dto;

import com.fasterxml.jackson.annotation.JsonAnyGetter;
import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.Data;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * STT 요약 LLM 응답 ({@code transcribe_summarize.SUMMARIZE_SYSTEM_PROMPT} 출력 JSON 그대로).
 *
 * <p>핵심 필드만 명시 매핑하고, 나머지(speaker_A/B, dialogue, actions_taken 등)는 {@link #raw}에 함께 보존.
 * AIA_CALL_ANALYSIS upsert와 응답 JSON 양쪽에서 사용.
 */
@Data
public class CallSummaryDto {

    /** report — KOK_CALL_MNTR.REPORT 와 동일 포맷("문의자 / 연락처 / 문의내역" 3줄). */
    private String report;

    /** feedback — 상담사 답변 통합 서술. */
    private String feedback;

    /** AIA 매핑용 코드성 필드들. */
    private String systemCd;
    private String systemTp;
    private String urgencyCd;
    private String category;

    /** 자유 서술 필드. */
    private String summary;
    private String hotelNm;
    private String callerNm;

    /** 명시 필드 외 LLM이 반환한 모든 키(speaker_A/B, dialogue, question, ...)는 여기에 보존. */
    @JsonIgnore
    private final Map<String, Object> raw = new LinkedHashMap<>();

    @JsonAnyGetter
    public Map<String, Object> getRaw() { return raw; }

    /** Jackson이 명시 필드 외 키를 raw에 모아주는 핸들러. snake_case → camelCase 명시 매핑도 여기서. */
    @JsonAnySetter
    public void put(String key, Object value) {
        switch (key) {
            case "system_cd"  -> this.systemCd  = asString(value);
            case "system_tp"  -> this.systemTp  = asString(value);
            case "urgency_cd" -> this.urgencyCd = asString(value);
            case "hotel_nm"   -> this.hotelNm   = asString(value);
            case "caller_nm"  -> this.callerNm  = asString(value);
            // system_con(접수내용 코드) 등 기타 키는 raw에만 보관
            default -> raw.put(key, value);
        }
    }

    private static String asString(Object v) { return v == null ? null : v.toString(); }
}
