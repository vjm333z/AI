package com.recallai.dto;

import lombok.Data;
import org.springframework.jdbc.core.RowMapper;

@Data
public class CallQueueDto {

    private Long callId;
    private String audioFile;
    private String callerNo;
    private String receiverNo;
    private String callDt;
    private String propCd;
    private String cmpxCd;
    private Integer callDuration;
    private String sttRaw;
    private String sttReport;
    private String sttFeedback;
    private String sttSummary;
    private String callerNm;
    private String contactNo;
    private String category;
    private String resolveStatus;
    private String status;
    private Integer linkedSeqNo;
    private Long parentCallId;
    private String createdDt;
    private String updatedDt;

    public static final RowMapper<CallQueueDto> ROW_MAPPER = (rs, rowNum) -> {
        CallQueueDto d = new CallQueueDto();
        d.setCallId(rs.getLong("call_id"));
        d.setAudioFile(rs.getString("audio_file"));
        d.setCallerNo(rs.getString("caller_no"));
        d.setReceiverNo(rs.getString("receiver_no"));
        d.setCallDt(rs.getString("call_dt"));
        d.setPropCd(rs.getString("prop_cd"));
        d.setCmpxCd(rs.getString("cmpx_cd"));
        d.setCallDuration(rs.getObject("call_duration") != null ? rs.getInt("call_duration") : null);
        d.setSttRaw(rs.getString("stt_raw"));
        d.setSttReport(rs.getString("stt_report"));
        d.setSttFeedback(rs.getString("stt_feedback"));
        d.setSttSummary(rs.getString("stt_summary"));
        d.setCallerNm(rs.getString("caller_nm"));
        d.setContactNo(rs.getString("contact_no"));
        d.setCategory(rs.getString("category"));
        d.setResolveStatus(rs.getString("resolve_status"));
        d.setStatus(rs.getString("status"));
        d.setLinkedSeqNo(rs.getObject("linked_seq_no") != null ? rs.getInt("linked_seq_no") : null);
        d.setParentCallId(rs.getObject("parent_call_id") != null ? rs.getLong("parent_call_id") : null);
        d.setCreatedDt(rs.getString("created_dt"));
        d.setUpdatedDt(rs.getString("updated_dt"));
        return d;
    };
}
