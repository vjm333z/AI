package com.ragtest.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.entity.ByteArrayEntity;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.util.EntityUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.*;

@Service
public class ClaudeService {

    @Value("${groq.api-key}")
    private String apiKey;

    @Value("${groq.model}")
    private String model;

    @Value("${groq.url}")
    private String groqUrl;

    private final ObjectMapper objectMapper = new ObjectMapper();

    public String ask(String question, List<Map<String, Object>> similarCases) throws Exception {
        // system 프롬프트: 역할과 규칙 정의
        String systemPrompt =
                "너는 호텔 PMS 고객 문의 답변 도우미야.\n" +
                "아래 규칙을 반드시 지켜:\n" +
                "1. 과거 유사 사례의 해결 방법을 참고해서 답변 방향을 제시해.\n" +
                "2. '처리 완료', '해결 완료' 같은 단정적 표현은 절대 쓰지 마.\n" +
                "3. '확인이 필요합니다', '담당자가 검토할 예정입니다' 형식으로 써.\n" +
                "4. 유사 사례가 없으면 '관련 사례를 찾지 못했습니다. 담당자에게 전달하겠습니다.'로 답변해.\n" +
                "5. 반드시 한국어로만 답변해.\n" +
                "6. 3~5줄 이내로 간결하게 작성해.";

        // 유사 사례 컨텍스트 구성
        StringBuilder context = new StringBuilder();
        if (similarCases.isEmpty()) {
            context.append("유사 사례 없음.\n");
        } else {
            context.append("=== 유사 과거 사례 ===\n\n");
            for (int i = 0; i < similarCases.size(); i++) {
                Map<String, Object> caseData = similarCases.get(i);
                Map<String, Object> payload = (Map<String, Object>) caseData.get("payload");
                Double score = (Double) caseData.get("score");

                context.append("[사례 ").append(i + 1).append("] (유사도: ")
                       .append(String.format("%.1f%%", score * 100)).append(")\n");
                context.append("문제: ").append(cleanText(String.valueOf(payload.get("report")))).append("\n");
                context.append("해결: ").append(cleanText(String.valueOf(payload.get("feedback")))).append("\n\n");
            }
        }

        // user 메시지: 컨텍스트 + 질문
        String userPrompt = context.toString() +
                "=== 현재 문의 ===\n" +
                question;

        CloseableHttpClient client = HttpClients.createDefault();
        HttpPost post = new HttpPost(groqUrl);
        post.setHeader("Authorization", "Bearer " + apiKey);
        post.setHeader("Content-Type", "application/json");

        Map<String, Object> systemMessage = new HashMap<>();
        systemMessage.put("role", "system");
        systemMessage.put("content", systemPrompt);

        Map<String, Object> userMessage = new HashMap<>();
        userMessage.put("role", "user");
        userMessage.put("content", userPrompt);

        List<Map<String, Object>> messages = new ArrayList<>();
        messages.add(systemMessage);
        messages.add(userMessage);

        Map<String, Object> body = new HashMap<>();
        body.put("model", model);
        body.put("messages", messages);
        body.put("max_tokens", 500);

        byte[] jsonBytes = objectMapper.writeValueAsBytes(body);
        ByteArrayEntity entity = new ByteArrayEntity(jsonBytes);
        entity.setContentType("application/json");
        post.setEntity(entity);

        CloseableHttpResponse response = client.execute(post);
        String responseBody = EntityUtils.toString(response.getEntity(), "UTF-8");
        client.close();

        System.out.println("Groq 응답: " + responseBody);

        JsonNode root = objectMapper.readTree(responseBody);
        return root.get("choices").get(0).get("message").get("content").asText();
    }

    private String cleanText(String text) {
        if (text == null || "null".equals(text)) return "";
        return text.replaceAll("<[^>]*>", " ")
                   .replaceAll("\\s+", " ")
                   .trim();
    }
}