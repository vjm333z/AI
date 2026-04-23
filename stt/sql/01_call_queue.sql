-- 통화 처리 대기열 및 STT 결과 저장 테이블
CREATE TABLE IF NOT EXISTS CALL_QUEUE (
    call_id         BIGINT AUTO_INCREMENT PRIMARY KEY,

    -- KT RPA 메타데이터 (파일명에서 파싱)
    caller_no       VARCHAR(20),            -- 발신번호
    receiver_no     VARCHAR(20),            -- 수신번호
    call_dt         DATETIME,               -- 통화 일시

    -- 호텔 정보 (STT → hotels.json 매칭)
    prop_cd         VARCHAR(10),
    cmpx_cd         VARCHAR(5),

    -- STT 결과
    call_duration   INT,                    -- 통화 시간 (초)
    stt_raw         MEDIUMTEXT,             -- STT 원문 전체
    stt_report      TEXT,                   -- 문의자/연락처/문의내역 (상담업무등록 자동입력용)
    stt_feedback    TEXT,                   -- 피드백 추천 초안
    stt_summary     TEXT,                   -- 3줄 요약
    caller_nm       VARCHAR(100),           -- 문의자명 (report 파싱)
    contact_no      VARCHAR(20),            -- 연락처 (report 파싱)
    category        VARCHAR(50),            -- 키오스크/객실키/결제·카드/체크인/PMS기능/부킹엔진/소모품·시재/기타
    resolve_status  VARCHAR(20),            -- 해결됨/처리중/추가조사필요/미확인

    -- 처리 상태
    status          VARCHAR(20) DEFAULT 'PENDING',
                                            -- PENDING: 대기 / REGISTERED: 등록완료 / SKIPPED: 건너뜀

    -- 연결 정보
    linked_seq_no   INT,                    -- 등록된 KOK_CALL_MNTR.SEQ_NO
    parent_call_id  BIGINT,                 -- 이전 연속 통화 ID

    -- 메타
    created_dt      DATETIME DEFAULT CURRENT_TIMESTAMP,
    updated_dt      DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    reg_id          VARCHAR(50) DEFAULT 'STT_AUTO'
);

-- 동일 통화 중복 방지 (upsert 키)
CREATE UNIQUE INDEX uq_cq_call ON CALL_QUEUE (caller_no, receiver_no, call_dt);

CREATE INDEX idx_cq_caller_no  ON CALL_QUEUE (caller_no);
CREATE INDEX idx_cq_prop_cmpx  ON CALL_QUEUE (prop_cd, cmpx_cd, call_dt);
CREATE INDEX idx_cq_status     ON CALL_QUEUE (status);
CREATE INDEX idx_cq_call_dt    ON CALL_QUEUE (call_dt DESC);
