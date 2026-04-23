package com.recallai.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.http.client.config.RequestConfig;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.entity.ByteArrayEntity;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.util.EntityUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import java.util.*;

/**
 * bge-reranker-v2-m3 (Python FastAPI) HTTP 클라이언트.
 * rag.reranker.enabled=true 인 경우 RagService에서 사용.
 */
@Service
public class RerankerService {

    private static final Logger log = LoggerFactory.getLogger(RerankerService.class);

    @Value("${rag.reranker.url}")
    private String rerankerUrl;

    @Value("${rag.reranker.timeout-ms:5000}")
    private int timeoutMs;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private CloseableHttpClient httpClient;

    @PostConstruct
    private void init() {
        RequestConfig config = RequestConfig.custom()
                .setConnectTimeout(timeoutMs)
                .setSocketTimeout(timeoutMs)
                .build();
        this.httpClient = HttpClients.custom().setDefaultRequestConfig(config).build();
    }

    @PreDestroy
    private void destroy() {
        try { if (httpClient != null) httpClient.close(); } catch (Exception ignored) {}
    }

    /**
     * 유사 사례들을 쿼리에 대한 관련도로 재정렬하고 상위 topK를 반환.
     * 반환 항목은 입력 similarCases 중 선택된 것들이며 "rerank_score" 필드가 추가됨.
     */
    public List<Map<String, Object>> rerank(String query,
                                            List<Map<String, Object>> similarCases,
                                            int topK) throws Exception {
        if (similarCases == null || similarCases.isEmpty()) {
            return Collections.emptyList();
        }

        List<String> documents = new ArrayList<>(similarCases.size());
        for (Map<String, Object> c : similarCases) {
            Map<String, Object> payload = (Map<String, Object>) c.get("payload");
            // 임베딩 입력(toEmbeddingText)과 일치시킴 — 현재는 REPORT만 사용.
            // TITLE은 payload에서 완전히 제거됨(2026-04-20).
            String report = payload.get("report") != null ? String.valueOf(payload.get("report")) : "";
            // HTML 태그/연속 공백 정리 (payload가 이미 cleanDisplay 적용됐더라도 방어적으로)
            documents.add(report.replaceAll("<[^>]*>", " ").replaceAll("\\s+", " ").trim());
        }

        Map<String, Object> body = new HashMap<>();
        body.put("query", query);
        body.put("documents", documents);
        body.put("top_k", topK);

        HttpPost post = new HttpPost(rerankerUrl + "/rerank");
        post.setHeader("Content-Type", "application/json");
        byte[] jsonBytes = objectMapper.writeValueAsBytes(body);
        ByteArrayEntity entity = new ByteArrayEntity(jsonBytes);
        entity.setContentType("application/json");
        post.setEntity(entity);

        try (CloseableHttpResponse response = httpClient.execute(post)) {
            String responseBody = EntityUtils.toString(response.getEntity(), "UTF-8");
            int status = response.getStatusLine().getStatusCode();
            if (status < 200 || status >= 300) {
                throw new RuntimeException("Reranker 호출 실패 status=" + status + " body=" + responseBody);
            }
            log.debug("Reranker 응답: {}", responseBody);

            JsonNode root = objectMapper.readTree(responseBody);
            JsonNode results = root.get("results");

            List<Map<String, Object>> reranked = new ArrayList<>();
            if (results != null) {
                for (JsonNode item : results) {
                    int idx = item.get("index").asInt();
                    double rerankScore = item.get("score").asDouble();
                    if (idx >= 0 && idx < similarCases.size()) {
                        Map<String, Object> picked = new HashMap<>(similarCases.get(idx));
                        picked.put("rerank_score", rerankScore);
                        reranked.add(picked);
                    }
                }
            }
            return reranked;
        }
    }
}
