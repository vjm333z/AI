package com.recallai.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * python-svc {@code /stt} 응답.
 *
 * <p>{@code segments}는 회사명·도메인 용어 보정({@code corrections.fix_company_name}) 적용 후 값.
 * 호텔 별칭 보정(hotels.json aliases)은 Spring 쪽 HotelMatcherService에서 추가 적용.
 */
@Data
@JsonInclude(JsonInclude.Include.NON_NULL)
public class SttResultDto {
    private String text;
    private List<SttSegmentDto> segments = new ArrayList<>();

    @JsonProperty("duration_sec")
    private double durationSec;

    private String language;

    @JsonProperty("language_probability")
    private double languageProbability;
}
