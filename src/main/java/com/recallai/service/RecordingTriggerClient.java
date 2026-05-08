package com.recallai.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.recallai.config.SttProperties;
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
 * python-svc {@code POST /process} 트리거 (fire-and-forget). 응답은 202만 확인.
 */
@Service
@RequiredArgsConstructor
public class RecordingTriggerClient {

    private static final Logger log = LoggerFactory.getLogger(RecordingTriggerClient.class);

    private final SttProperties props;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private CloseableHttpClient httpClient;

    @PostConstruct
    private void init() {
        RequestConfig config = RequestConfig.custom()
                .setConnectTimeout(3000)
                .setSocketTimeout(props.getTimeoutMs())
                .build();
        this.httpClient = HttpClients.custom().setDefaultRequestConfig(config).build();
    }

    @PreDestroy
    private void destroy() {
        try { if (httpClient != null) httpClient.close(); } catch (Exception ignored) {}
    }

    /**
     * 파일 처리 트리거.
     * @param relativePath {@code DATA_DIR} 기준 상대 경로 (예: {@code "recordings/inbox/foo.mp3"})
     */
    public void triggerProcess(String relativePath) throws Exception {
        HttpPost post = new HttpPost(props.getUrl() + "/process");
        post.setHeader("Content-Type", "application/json; charset=utf-8");

        byte[] body = objectMapper.writeValueAsBytes(Map.of("relative_path", relativePath));
        ByteArrayEntity entity = new ByteArrayEntity(body);
        entity.setContentType("application/json; charset=utf-8");
        post.setEntity(entity);

        try (CloseableHttpResponse res = httpClient.execute(post)) {
            int status = res.getStatusLine().getStatusCode();
            if (status < 200 || status >= 300) {
                String resBody = EntityUtils.toString(res.getEntity(), "UTF-8");
                throw new RuntimeException("python-svc /process 트리거 실패 status=" + status + " body=" + resBody);
            }
            EntityUtils.consume(res.getEntity());
            log.info("python-svc 트리거 완료: {}", relativePath);
        }
    }
}
