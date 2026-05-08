package com.recallai.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * python-svc {@code /stt} HTTP 클라이언트 설정.
 *
 * <p>{@code stt.url} 은 python-svc 컨테이너 베이스 URL (reranker와 같은 컨테이너).
 * 현재 docker-compose에선 {@code http://python-svc:8000}, 로컬 dev에선 {@code http://localhost:8000}.
 */
@Data
@ConfigurationProperties(prefix = "stt")
public class SttProperties {
    private String url;
    /** Groq Whisper 호출 자체는 빠르지만 큐잉·rate limit 재시도 여유 포함. */
    private int timeoutMs = 180_000;
}
