package com.recallai.dto;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Data
public class ComplexDto {
    private String propCd;
    private String cmpxCd;
    private String cmpxNm;
    private String cmpxTp;
    private String cmpxReprTel;
    /** DB에 없는 휴대폰 번호 — hotels.json 수동 편집으로 관리. refresh()는 기존 값 유지. */
    private List<String> mobileNos = new ArrayList<>();

    public void setMobileNos(List<String> mobileNos) {
        this.mobileNos = mobileNos != null ? mobileNos : new ArrayList<>();
    }
}
