package com.recallai.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 통화 녹음 수신 설정. application.yml의 {@code recording.*}.
 * 경로는 {@link RagProperties#getDataDir()} 기준 상대 경로.
 */
@Data
@ConfigurationProperties(prefix = "recording")
public class RecordingProperties {

    /** data-dir 하위 녹음 라이프사이클 루트. {@code inbox/} 가 그 아래에 생성됨. */
    private String baseDir = "recordings";
}
