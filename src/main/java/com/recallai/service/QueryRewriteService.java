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

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Groq로 사용자 질문을 검색 친화적 문장으로 확장.
 * 예) "PMS 접속 안돼요" → "PMS 로그인 실패, 인증 오류, 네트워크 연결 문제, 비밀번호 오류"
 * 실패 시 원문 질문을 그대로 반환 (서비스 중단 방지).
 */
@Service
public class QueryRewriteService {

    private static final Logger log = LoggerFactory.getLogger(QueryRewriteService.class);

    @Value("${groq.api-key}")
    private String apiKey;

    @Value("${groq.model}")
    private String model;

    @Value("${groq.url}")
    private String groqUrl;

    @Value("${rag.query-rewrite.timeout-ms:3000}")
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

    private static final String SYSTEM_PROMPT =
            "너는 호텔 PMS 고객 문의를 벡터 검색용 쿼리로 재작성하는 도우미야.\n" +
            "아래 규칙을 반드시 지켜:\n" +
            "1. 원본 질문의 의도를 유지하면서, 동의어/관련 용어를 자연스럽게 추가해.\n" +
            "2. 호텔 PMS 도메인(예약, 체크인/체크아웃, 객실, 취소, 환불, 결제, 로그인, PMS, 인증 등) " +
            "관련 질문이면 해당 용어를 활용해. **도메인과 무관한 질문이면 도메인 용어를 억지로 끼워넣지 말고 " +
            "원본 질문을 거의 그대로(동의어 정도만 추가) 반환해.**\n" +
            "3. 추측성 원인·해결책은 쓰지 말고 '어떤 유형의 문의인지'만 표현해.\n" +
            "4. 재작성 결과만 출력. 설명, 따옴표, 머리말 없이 한 줄로만 써.\n" +
            "5. 한국어로 작성.\n" +
            "6. 120자 이내.\n" +
            "\n" +
            "[예시]\n" +
            "- '예약 취소가 안돼요' → '예약 취소 실패, 취소 처리 오류, 환불 요청' (도메인 내 → 확장)\n" +
            "- '방구냄새가 심해요' → '객실 냄새 민원, 악취 불만' (도메인 밖 → 원문 의도 유지, 억지 PMS 용어 금지)";

    public String rewrite(String original) {
        if (original == null || original.trim().isEmpty()) {
            return original;
        }

        try {
            HttpPost post = new HttpPost(groqUrl);
            post.setHeader("Authorization", "Bearer " + apiKey);
            post.setHeader("Content-Type", "application/json");

            Map<String, Object> systemMessage = new HashMap<>();
            systemMessage.put("role", "system");
            systemMessage.put("content", SYSTEM_PROMPT);

            Map<String, Object> userMessage = new HashMap<>();
            userMessage.put("role", "user");
            userMessage.put("content", "원본 질문: " + original);

            List<Map<String, Object>> messages = new ArrayList<>();
            messages.add(systemMessage);
            messages.add(userMessage);

            Map<String, Object> body = new HashMap<>();
            body.put("model", model);
            body.put("messages", messages);
            body.put("max_tokens", 150);
            body.put("temperature", 0.3);

            byte[] jsonBytes = objectMapper.writeValueAsBytes(body);
            ByteArrayEntity entity = new ByteArrayEntity(jsonBytes);
            entity.setContentType("application/json");
            post.setEntity(entity);

            try (CloseableHttpResponse response = httpClient.execute(post)) {
                String responseBody = EntityUtils.toString(response.getEntity(), "UTF-8");
                int status = response.getStatusLine().getStatusCode();
                if (status < 200 || status >= 300) {
                    throw new RuntimeException("Groq (query rewrite) 호출 실패 status=" + status + " body=" + responseBody);
                }
                JsonNode root = objectMapper.readTree(responseBody);
                String rewritten = root.get("choices").get(0).get("message").get("content").asText().trim();

                // LLM이 따옴표 감싸거나 줄바꿈 넣으면 정리
                rewritten = rewritten.replaceAll("^[\"']|[\"']$", "").replaceAll("\\s+", " ").trim();
                if (rewritten.isEmpty()) {
                    return original;
                }

                log.info("Query rewrite: '{}' → '{}'", original, rewritten);
                return rewritten;
            }
        } catch (Exception e) {
            log.warn("Query rewrite 실패, 원문 사용: {} (원인: {})", original, e.getMessage());
            return original;
        }
    }
}
