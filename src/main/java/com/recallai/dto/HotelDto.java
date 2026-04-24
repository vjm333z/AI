package com.recallai.dto;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Data
public class HotelDto {
    private String propCd;
    private String propShrtNm;
    private String propFullNm;
    private List<ComplexDto> complexes = new ArrayList<>();
    /** Whisper 오인식 보정용 별칭 — hotels.json 수동 편집으로 관리. refresh()는 기존 값 유지. */
    private List<String> aliases = new ArrayList<>();

    public void setAliases(List<String> aliases) {
        this.aliases = aliases != null ? aliases : new ArrayList<>();
    }
}
