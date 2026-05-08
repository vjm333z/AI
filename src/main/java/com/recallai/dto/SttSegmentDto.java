package com.recallai.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;

/** Whisper STT 한 세그먼트 — start/end 초 + 텍스트. python-svc {@code /stt} 응답에 그대로 매핑. */
@Data
@JsonInclude(JsonInclude.Include.NON_NULL)
public class SttSegmentDto {
    private double start;
    private double end;
    private String text;
}
