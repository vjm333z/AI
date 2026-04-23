package com.recallai.dto;

import java.util.ArrayList;
import java.util.List;

public class HotelDto {
    private String propCd;
    private String propShrtNm;
    private String propFullNm;
    private List<ComplexDto> complexes = new ArrayList<>();
    /** Whisper 오인식 보정용 별칭 — hotels.json 수동 편집으로 관리. refresh()는 기존 값 유지. */
    private List<String> aliases = new ArrayList<>();

    public String getPropCd() { return propCd; }
    public void setPropCd(String propCd) { this.propCd = propCd; }

    public String getPropShrtNm() { return propShrtNm; }
    public void setPropShrtNm(String propShrtNm) { this.propShrtNm = propShrtNm; }

    public String getPropFullNm() { return propFullNm; }
    public void setPropFullNm(String propFullNm) { this.propFullNm = propFullNm; }

    public List<ComplexDto> getComplexes() { return complexes; }
    public void setComplexes(List<ComplexDto> complexes) { this.complexes = complexes; }

    public List<String> getAliases() { return aliases; }
    public void setAliases(List<String> aliases) { this.aliases = aliases != null ? aliases : new ArrayList<>(); }
}
