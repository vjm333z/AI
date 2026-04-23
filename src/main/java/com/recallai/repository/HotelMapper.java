package com.recallai.repository;

import com.recallai.dto.ComplexDto;
import com.recallai.dto.HotelDto;
import org.apache.ibatis.annotations.Mapper;

import java.util.List;

@Mapper
public interface HotelMapper {
    List<HotelDto> selectAllProperties();
    List<ComplexDto> selectAllComplexes();
}
