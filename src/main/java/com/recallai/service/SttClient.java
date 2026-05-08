package com.recallai.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.recallai.config.SttProperties;
import com.recallai.dto.SttResultDto;
import lombok.RequiredArgsConstructor;
import org.apache.http.client.config.RequestConfig;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.entity.ByteArrayEntity;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.util.EntityUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import java.util.Map;

/**
 * python-svc {@code POST /stt} HTTP 클라이언트.
 *
 * <p>파일은 공유 볼륨(앱과 python-svc가 같은 {@code /app/data}를 마운트)에 이미 저장돼 있다고 가정.
 * Spring은 data-dir 기준 상대 경로만 전달하고, python-svc는 자신의 {@code DATA_DIR}로 해석.
 */
@Service
@RequiredArgsConstructor
public class SttClient {

    private static final Logger log = LoggerFactory.getLogger(SttClient.class);

    private final SttProperties props;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private CloseableHttpClient httpClient;

    @PostConstruct
    private void init() {
        var config = RequestConfig.custom()
                .setConnectTimeout(5000)
                .setSocketTimeout(props.getTimeoutMs())
                .build();
        this.httpClient = HttpClients.custom().setDefaultRequestConfig(config).build();
    }

    @PreDestroy
    private void destroy() {
        try { if (httpClient != null) httpClient.close(); } catch (Exception ignored) {}
    }

    /**
     * 공유 볼륨에 저장된 음성 파일을 Whisper로 전사.
     * @param relativePath data-dir 기준 상대 경로 (예: {@code recordings/inbox/01072834221-...mp3})
     */
    public SttResultDto transcribe(String relativePath) throws Exception {
        var post = new HttpPost(props.getUrl() + "/stt");
        post.setHeader("Content-Type", "application/json");

        byte[] body = objectMapper.writeValueAsBytes(Map.of("relative_path", relativePath));
        var entity = new ByteArrayEntity(body);
        entity.setContentType("application/json");
        post.setEntity(entity);

        long started = System.currentTimeMillis();
        try (CloseableHttpResponse response = httpClient.execute(post)) {
            String responseBody = EntityUtils.toString(response.getEntity(), "UTF-8");
            int status = response.getStatusLine().getStatusCode();
            if (status < 200 || status >= 300) {
                throw new RuntimeException("STT 호출 실패 status=" + status + " body=" + responseBody);
            }
            SttResultDto result = objectMapper.readValue(responseBody, SttResultDto.class);
            log.info("[STT] {} → 음성 {}초, 세그먼트 {}개, 처리 {}ms",
                    relativePath, String.format("%.1f", result.getDurationSec()),
                    result.getSegments() == null ? 0 : result.getSegments().size(),
                    System.currentTimeMillis() - started);
            return result;
        }
    }
}
