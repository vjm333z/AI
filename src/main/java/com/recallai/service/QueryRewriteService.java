package com.recallai.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.http.client.config.RequestConfig;
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
        var config = RequestConfig.custom()
                .setConnectTimeout(timeoutMs)
                .setSocketTimeout(timeoutMs)
                .build();
        this.httpClient = HttpClients.custom().setDefaultRequestConfig(config).build();
    }

    @PreDestroy
    private void destroy() {
        try { if (httpClient != null) httpClient.close(); } catch (Exception ignored) {}
    }

    private static final String SYSTEM_PROMPT = """
            너는 호텔 PMS 상담 문의를 벡터 유사도 검색에 최적화된 쿼리로 재작성하는 전문가야.

            [목표]
            원본 문의와 동일한 의미의 과거 상담 사례를 잘 찾을 수 있도록, \
            핵심 개념·동의어·관련 용어를 최대한 폭넓게 나열해.

            [규칙]
            1. 핵심 키워드를 먼저 쓰고, 동의어·유사 표현·관련 용어를 쉼표로 나열해.
            2. 호텔 PMS 도메인 용어(예약, 체크인, 객실, 결제, 로그인, 비밀번호, 키오스크, SMS, 문자발송, 인증 등)를 적극 활용해.
            3. 증상·현상·요청 유형을 모두 포함해 (예: "오류" → "오류, 에러, 장애, 안됨, 실패").
            4. 재작성 결과만 출력. 설명·따옴표·머리말 없이 한 줄로만.
            5. 한국어. 150자 이내.

            [예시]
            - '예약 취소가 안돼요' → '예약 취소 실패, 취소 처리 오류, 예약 삭제 안됨, 환불 요청, 취소 불가'
            - '로그인 비밀번호 문자로 보내줘' → '로그인 비밀번호 발송, 임시 비밀번호 SMS 전송, 비밀번호 안내 문자, 초기 비밀번호 문자발송, 계정 비밀번호 알림'
            - '키오스크 화면이 꺼져있어요' → '키오스크 화면 꺼짐, 키오스크 블랙스크린, 키오스크 오류, 화면 미표시, 장비 전원 문제'
            """;

    public String rewrite(String original) {
        if (original == null || original.trim().isEmpty()) {
            return original;
        }

        try {
            var post = new HttpPost(groqUrl);
            post.setHeader("Authorization", "Bearer " + apiKey);
            post.setHeader("Content-Type", "application/json");

            var body = Map.of(
                "model", model,
                "messages", List.of(
                    Map.of("role", "system", "content", SYSTEM_PROMPT),
                    Map.of("role", "user", "content", "원본 질문: " + original)
                ),
                "max_tokens", 150,
                "temperature", 0,
                "seed", 42
            );

            var jsonBytes = objectMapper.writeValueAsBytes(body);
            var entity = new ByteArrayEntity(jsonBytes);
            entity.setContentType("application/json");
            post.setEntity(entity);

            try (var response = httpClient.execute(post)) {
                var responseBody = EntityUtils.toString(response.getEntity(), "UTF-8");
                int status = response.getStatusLine().getStatusCode();
                if (status < 200 || status >= 300) {
                    throw new RuntimeException("Groq (query rewrite) 호출 실패 status=" + status + " body=" + responseBody);
                }
                var root = objectMapper.readTree(responseBody);
                var rewritten = root.get("choices").get(0).get("message").get("content").asText().trim();

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
