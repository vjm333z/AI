package com.recallai.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * python-svc {@code /process} 트리거 클라이언트 설정.
 * {@code stt.url} = python-svc 베이스 URL ({@code http://python-svc:8000}, dev: {@code http://localhost:8000}).
 */
@Data
@ConfigurationProperties(prefix = "stt")
public class SttProperties {
    private String url;
    /** 트리거는 큐 등록만 하고 즉시 202 반환되므로 짧게. */
    private int timeoutMs = 10_000;
}
