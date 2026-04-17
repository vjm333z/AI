package com.ragtest.repository;

import com.ragtest.dto.KokCallMntrDto;
import org.apache.ibatis.annotations.Mapper;

import java.util.List;

@Mapper
public interface KokCallMntrMapper {
    List<KokCallMntrDto> selectAll();

    List<KokCallMntrDto> selectAfter(String lastSyncDt);
}
