package com.recallai.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

/**
 * 통화 녹음 처리 결과 — 파일 메타 + STT + 요약 + 호텔 매칭.
 *
 * <p>JSON 사본({@code data/recordings/results/<stem>.json})과 AIA_CALL_* 테이블에 동일 모양으로 사용.
 * 필드명은 기존 python-svc 결과 JSON과 호환을 위해 snake_case 직렬화.
 */
@Data
@JsonInclude(JsonInclude.Include.NON_NULL)
public class RecordingResult {

    @JsonProperty("audio_file")    private String audioFile;
    @JsonProperty("base_file_nm")  private String baseFileNm;
    @JsonProperty("file_path")     private String filePath;
    @JsonProperty("file_size")     private Long fileSize;
    @JsonProperty("sha256")        private String sha256;

    @JsonProperty("caller_no")     private String callerNo;
    @JsonProperty("receiver_no")   private String receiverNo;
    @JsonProperty("call_dt")       private String callDt;
    @JsonProperty("channel_seq")   private Integer channelSeq;

    @JsonProperty("prop_cd")       private String propCd;
    @JsonProperty("cmpx_cd")       private String cmpxCd;
    @JsonProperty("cmpx_nm")       private String cmpxNm;

    @JsonProperty("stt")           private SttResultDto stt;
    @JsonProperty("summary")       private CallSummaryDto summary;

    @JsonProperty("rec_seq_no")    private Integer recSeqNo;
}
