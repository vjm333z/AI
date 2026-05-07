package com.recallai.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.recallai.util.TextSanitizer;
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
import java.util.List;
import java.util.Map;

/**
 * OpenAI gpt-4o-mini — Groq 폴백용 RAG 답변 생성기.
 * RagService.ask() 가 Groq 호출 실패 시 본 서비스를 호출.
 */
@Service
public class OpenAiService {

    private static final Logger log = LoggerFactory.getLogger(OpenAiService.class);

    @Value("${openai.api-key}")
    private String apiKey;

    @Value("${openai.model:gpt-4o-mini}")
    private String model;

    @Value("${openai.url:https://api.openai.com/v1/chat/completions}")
    private String openAiUrl;

    @Value("${openai.timeout-ms:30000}")
    private int timeoutMs;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private CloseableHttpClient httpClient;

    @PostConstruct
    private void init() {
        var config = RequestConfig.custom()
                .setConnectTimeout(5000)
                .setSocketTimeout(timeoutMs)
                .build();
        this.httpClient = HttpClients.custom().setDefaultRequestConfig(config).build();
    }

    @PreDestroy
    private void destroy() {
        try { if (httpClient != null) httpClient.close(); } catch (Exception ignored) {}
    }

    private static final String SYSTEM_PROMPT = """
            너는 호텔 PMS 고객 문의에 과거 사례를 참고해 답변하는 전문 도우미야.

            [답변 기준 — 반드시 지켜]
            1. 각 과거 사례의 유사도·재정렬점수를 보고 참고 여부를 판단해:
               - 85%+: 매우 유사. 해당 사례의 해결 방식을 적극 참고해 구체적으로 답변.
               - 70~85%: 유사. '유사 사례를 바탕으로 추정하면' 같은 표현으로 참고.
               - 70% 미만: 관련성 낮음. 이 사례는 무시하고 언급도 하지 마.
            2. 질문과 관련 없어 보이는 사례는 인용하지 마. 억지로 끼워맞추지 마.
            3. 과거 사례에 실제 해결 방법이 있으면 그대로 안내하고, 없거나 모호하면 '담당자 확인이 필요합니다'.
            4. '처리 완료'·'해결 완료' 같은 단정적 표현 금지. 과거 사례에서 실제 있었던 조치만 인용.
            5. 전달된 모든 사례가 70% 미만이거나 질문과 무관하면 '관련 사례를 찾지 못했습니다. 담당자에게 전달하겠습니다.'로만 답해.
            6. 반드시 한국어로만 작성. 한자(漢字)·일본어·중국어 문자 절대 사용 금지.
            7. 3~5줄 이내.
            """;

    public String ask(String question, List<Map<String, Object>> similarCases) throws Exception {
        if (similarCases == null || similarCases.isEmpty()) {
            return "관련 사례를 찾지 못했습니다. 담당자에게 전달하겠습니다.";
        }

        var context = new StringBuilder("=== 유사 과거 사례 ===\n\n");
        for (int i = 0; i < similarCases.size(); i++) {
            var caseData = similarCases.get(i);
            @SuppressWarnings("unchecked")
            var payload = (Map<String, Object>) caseData.get("payload");
            var score = ((Number) caseData.get("score")).doubleValue();
            var rerankScore = caseData.get("rerank_score");

            context.append("[사례 ").append(i + 1).append("] Qdrant 유사도: ")
                   .append(String.format("%.1f%%", score * 100));
            if (rerankScore instanceof Number n) {
                context.append(" | Reranker 관련도: ")
                       .append(String.format("%.1f%%", n.doubleValue() * 100));
            }
            context.append("\n");
            context.append("문제: ").append(TextSanitizer.cleanForPrompt(payload.get("report"))).append("\n");
            context.append("해결: ").append(TextSanitizer.cleanForPrompt(payload.get("feedback"))).append("\n\n");
        }
        String userPrompt = context + "=== 현재 문의 ===\n" + question;

        var post = new HttpPost(openAiUrl);
        post.setHeader("Authorization", "Bearer " + apiKey);
        post.setHeader("Content-Type", "application/json");

        var body = Map.of(
            "model", model,
            "messages", List.of(
                Map.of("role", "system", "content", SYSTEM_PROMPT),
                Map.of("role", "user",   "content", userPrompt)
            ),
            "max_tokens", 500
        );

        var jsonBytes = objectMapper.writeValueAsBytes(body);
        var entity = new ByteArrayEntity(jsonBytes);
        entity.setContentType("application/json");
        post.setEntity(entity);

        try (CloseableHttpResponse response = httpClient.execute(post)) {
            var responseBody = EntityUtils.toString(response.getEntity(), "UTF-8");
            int status = response.getStatusLine().getStatusCode();
            if (status < 200 || status >= 300) {
                throw new RuntimeException("OpenAI 호출 실패 status=" + status + " body=" + responseBody);
            }
            log.debug("OpenAI 응답: {}", responseBody);
            var root = objectMapper.readTree(responseBody);
            var choices = root.get("choices");
            if (choices == null || choices.isEmpty()) {
                throw new RuntimeException("OpenAI 응답에 choices 없음: " + responseBody);
            }
            return choices.get(0).get("message").get("content").asText();
        }
    }
}
