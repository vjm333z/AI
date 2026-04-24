package com.recallai.service;

import com.recallai.dto.CallQueueDto;
import com.recallai.dto.KokCallMntrDto;
import com.recallai.repository.KokCallMntrMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class CallQueueService {

    private static final Logger log = LoggerFactory.getLogger(CallQueueService.class);

    @Autowired
    @Qualifier("localJdbcTemplate")
    private JdbcTemplate jdbc;

    @Autowired
    private KokCallMntrMapper kokMapper;

    @Autowired
    private RagService ragService;

    /** 대기 목록 조회. status 생략 시 PENDING만. */
    public List<CallQueueDto> list(String status, int page, int size) {
        String s = (status != null && !status.isEmpty()) ? status : "PENDING";
        int offset = page * size;
        return jdbc.query(
            "SELECT call_id, audio_file, caller_no, receiver_no, call_dt, " +
            "prop_cd, cmpx_cd, call_duration, stt_raw, stt_report, stt_feedback, " +
            "stt_summary, caller_nm, contact_no, category, resolve_status, " +
            "status, linked_seq_no, parent_call_id, " +
            "DATE_FORMAT(created_dt,'%Y-%m-%d %H:%i:%s') AS created_dt, " +
            "DATE_FORMAT(updated_dt,'%Y-%m-%d %H:%i:%s') AS updated_dt " +
            "FROM CALL_QUEUE WHERE status = ? ORDER BY call_dt DESC LIMIT ? OFFSET ?",
            CallQueueDto.ROW_MAPPER, s, size, offset);
    }

    /** 단건 상세 조회 */
    public CallQueueDto get(Long callId) {
        List<CallQueueDto> rows = jdbc.query(
            "SELECT call_id, audio_file, caller_no, receiver_no, call_dt, " +
            "prop_cd, cmpx_cd, call_duration, stt_raw, stt_report, stt_feedback, " +
            "stt_summary, caller_nm, contact_no, category, resolve_status, " +
            "status, linked_seq_no, parent_call_id, " +
            "DATE_FORMAT(created_dt,'%Y-%m-%d %H:%i:%s') AS created_dt, " +
            "DATE_FORMAT(updated_dt,'%Y-%m-%d %H:%i:%s') AS updated_dt " +
            "FROM CALL_QUEUE WHERE call_id = ?",
            CallQueueDto.ROW_MAPPER, callId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /** report/feedback/prop_cd/cmpx_cd/category/resolve_status 수정 */
    public void update(Long callId, Map<String, Object> body) {
        jdbc.update(
            "UPDATE CALL_QUEUE SET " +
            "stt_report = COALESCE(?, stt_report), " +
            "stt_feedback = COALESCE(?, stt_feedback), " +
            "prop_cd = COALESCE(?, prop_cd), " +
            "cmpx_cd = COALESCE(?, cmpx_cd), " +
            "category = COALESCE(?, category), " +
            "resolve_status = COALESCE(?, resolve_status) " +
            "WHERE call_id = ?",
            str(body, "sttReport"),
            str(body, "sttFeedback"),
            str(body, "propCd"),
            str(body, "cmpxCd"),
            str(body, "category"),
            str(body, "resolveStatus"),
            callId);
    }

    /**
     * 승인 — KOK_CALL_MNTR INSERT → Qdrant 인덱싱 → CALL_QUEUE status=REGISTERED.
     * 재적재 비용 없이 단건만 인덱싱.
     */
    public Map<String, Object> approve(Long callId, String regId) throws Exception {
        CallQueueDto q = get(callId);
        if (q == null) throw new IllegalArgumentException("call_id 없음: " + callId);
        if (!"PENDING".equals(q.getStatus()))
            throw new IllegalStateException("이미 처리된 건입니다. status=" + q.getStatus());

        // KOK_CALL_MNTR 삽입용 DTO 구성
        KokCallMntrDto dto = new KokCallMntrDto();
        dto.setPropCd(q.getPropCd());
        dto.setCmpxCd(q.getCmpxCd());
        dto.setReport(q.getSttReport());
        dto.setFeedback(q.getSttFeedback());
        dto.setFeedbackYn("Y");
        dto.setRDt(q.getCallDt() != null ? q.getCallDt().substring(0, 10) : null);
        dto.setTitle(buildTitle(q));

        // PMS DB에 INSERT (MyBatis, useGeneratedKeys → dto.seqNo 채워짐)
        kokMapper.insertFromQueue(dto);
        int seqNo = dto.getSeqNo();
        log.info("KOK_CALL_MNTR INSERT 완료: seq_no={}, call_id={}", seqNo, callId);

        // Qdrant 단건 인덱싱
        try {
            ragService.indexSingle(dto);
        } catch (Exception e) {
            log.warn("Qdrant 인덱싱 실패 (DB 저장은 완료): seq_no={}, cause={}", seqNo, e.getMessage());
        }

        // CALL_QUEUE 상태 갱신
        jdbc.update(
            "UPDATE CALL_QUEUE SET status='REGISTERED', linked_seq_no=?, reg_id=? WHERE call_id=?",
            seqNo, regId != null ? regId : "PMS_APPROVE", callId);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("success", true);
        result.put("call_id", callId);
        result.put("seq_no", seqNo);
        return result;
    }

    /** 건너뜀 처리 */
    public void skip(Long callId) {
        jdbc.update("UPDATE CALL_QUEUE SET status='SKIPPED' WHERE call_id=?", callId);
    }

    private String buildTitle(CallQueueDto q) {
        if (q.getSttSummary() != null && !q.getSttSummary().isEmpty()) {
            String s = q.getSttSummary().split("\n")[0].trim();
            return s.length() > 100 ? s.substring(0, 100) : s;
        }
        if (q.getCategory() != null && !q.getCategory().isEmpty())
            return q.getCategory() + " 문의";
        return "통화 문의";
    }

    private String str(Map<String, Object> m, String key) {
        Object v = m.get(key);
        return v != null ? v.toString() : null;
    }
}
