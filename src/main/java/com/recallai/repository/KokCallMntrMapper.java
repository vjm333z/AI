package com.recallai.repository;

import com.recallai.dto.KokCallMntrDto;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.Collection;
import java.util.List;

@Mapper
public interface KokCallMntrMapper {

    /** 완결·피드백 보유 건 전체 — 적재용. */
    List<KokCallMntrDto> selectAll();

    /** lastSyncDt 이후 변경분 — 증분 적재용. */
    List<KokCallMntrDto> selectAfter(String lastSyncDt);

    /** 실패 재시도용 — seq_no 리스트로 원본 조회. 동일 필터 유지(데이터 변경 시 0건 반환). */
    List<KokCallMntrDto> selectBySeqNos(@Param("seqNos") Collection<Integer> seqNos);

    /** phone_lookup용 — PROP_CD + REPORT만 조회 (연락처 파싱용). */
    List<KokCallMntrDto> selectForPhoneLookup();
}
