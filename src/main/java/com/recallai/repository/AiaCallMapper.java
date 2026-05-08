package com.recallai.repository;

import org.apache.ibatis.annotations.Param;

import java.util.Map;

/**
 * AIA_CALL_* 3개 테이블 UPSERT 매퍼.
 *
 * <p>{@code AIA_CALL_RECORDING}은 {@code BASE_FILE_NM} UNIQUE → ON DUPLICATE KEY UPDATE.
 * {@code REC_SEQ_NO}는 AUTO_INCREMENT라 upsert 직후 별도 SELECT로 조회.
 * {@code TRANSCRIPT}/{@code ANALYSIS}는 {@code REC_SEQ_NO} PK + UPSERT.
 *
 * <p>다른 매퍼와 달리 {@code @Mapper} 가 없습니다 — 메인 PMS DB(178)에는 INSERT 권한이 없어서
 * STT 전용 DB(191) 에만 써야 함. {@link com.recallai.config.AiaDataSourceConfig} 가
 * 별도 SqlSessionFactory + 명시적 빈 등록으로 191 호스트에 연결.
 */
public interface AiaCallMapper {

    /** 동일 파일명이 이미 RECORDING에 존재하는지. dedup용. */
    Integer existsByBaseFileNm(@Param("baseFileNm") String baseFileNm);

    /** RECORDING UPSERT. BASE_FILE_NM 충돌 시 메타 갱신. */
    int upsertRecording(Map<String, Object> row);

    /** UPSERT 직후 REC_SEQ_NO 조회 (BASE_FILE_NM 으로). */
    Integer selectRecSeqNo(@Param("baseFileNm") String baseFileNm);

    /** TRANSCRIPT UPSERT (REC_SEQ_NO PK). */
    int upsertTranscript(Map<String, Object> row);

    /** ANALYSIS UPSERT (REC_SEQ_NO PK). 요약 성공 시에만 호출. */
    int upsertAnalysis(Map<String, Object> row);
}
