package com.recallai.dto;

import java.util.ArrayList;
import java.util.List;

public class ComplexDto {
    private String propCd;
    private String cmpxCd;
    private String cmpxNm;
    private String cmpxTp;
    private String cmpxReprTel;
    /** DB에 없는 휴대폰 번호 — hotels.json 수동 편집으로 관리. refresh()는 기존 값 유지. */
    private List<String> mobileNos = new ArrayList<>();

    public String getPropCd() { return propCd; }
    public void setPropCd(String propCd) { this.propCd = propCd; }

    public String getCmpxCd() { return cmpxCd; }
    public void setCmpxCd(String cmpxCd) { this.cmpxCd = cmpxCd; }

    public String getCmpxNm() { return cmpxNm; }
    public void setCmpxNm(String cmpxNm) { this.cmpxNm = cmpxNm; }

    public String getCmpxTp() { return cmpxTp; }
    public void setCmpxTp(String cmpxTp) { this.cmpxTp = cmpxTp; }

    public String getCmpxReprTel() { return cmpxReprTel; }
    public void setCmpxReprTel(String cmpxReprTel) { this.cmpxReprTel = cmpxReprTel; }

    public List<String> getMobileNos() { return mobileNos; }
    public void setMobileNos(List<String> mobileNos) { this.mobileNos = mobileNos != null ? mobileNos : new ArrayList<>(); }
}
