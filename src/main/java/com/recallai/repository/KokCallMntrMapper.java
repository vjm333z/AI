package com.recallai.repository;

import com.recallai.dto.KokCallMntrDto;
import org.apache.ibatis.annotations.Mapper;

import java.util.List;

@Mapper
public interface KokCallMntrMapper {
    List<KokCallMntrDto> selectAll();

    List<KokCallMntrDto> selectAfter(String lastSyncDt);

    /** 실패 재시도용 — seq_no 리스트 → FEEDBACK_YN·완결 필터 없이 원본 그대로 조회 */
    List<KokCallMntrDto> selectBySeqNos(@org.apache.ibatis.annotations.Param("seqNos") java.util.Collection<Integer> seqNos);

    /** phone_lookup용 — PROP_CD + REPORT만 조회 (연락처 파싱용) */
    List<KokCallMntrDto> selectForPhoneLookup();
}
