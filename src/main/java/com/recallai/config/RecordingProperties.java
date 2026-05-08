package com.recallai.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 통화 녹음 STT 파이프라인 설정. application.yml의 {@code recording.*}.
 *
 * <p>경로는 모두 {@link RagProperties#getDataDir()} 기준 상대 경로.
 * inbox/done/results 하위 디렉터리는 {@link com.recallai.service.RecordingPipelineService}에서 자동 생성.
 */
@Data
@ConfigurationProperties(prefix = "recording")
public class RecordingProperties {

    /** data-dir 하위 녹음 라이프사이클 루트. {@code inbox/}, {@code done/}, {@code results/} 가 그 아래 생성됨. */
    private String baseDir = "recordings";

    /** Groq Whisper 동시 호출 방지 전역 락. python-svc에도 동일 락이 있어 이중 안전망. */
    private boolean processLockEnabled = true;

    /** 이 길이 미만 통화는 잡음·오발신으로 간주하고 STT 후 스킵. */
    private int minDurationSec = 3;

    /** 요약 LLM provider — {@code openai} | {@code groq}. 실패 시 자동 폴백 (CallSummarizeService 참조). */
    private String summarizeProvider = "openai";

    /** 다올 비전(자사) 수신번호 — 연락처 자동 주입 시 이 번호는 제외. */
    private List<String> daolReceiverNos;

    /** O(1) 조회용 Set. application.yml 바인딩 직후 생성 — getter에서 lazy init. */
    public Set<String> getDaolReceiverNoSet() {
        return daolReceiverNos == null ? new HashSet<>() : new HashSet<>(daolReceiverNos);
    }
}
