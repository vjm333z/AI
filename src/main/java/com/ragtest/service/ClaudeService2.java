package com.ragtest.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.util.EntityUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.*;

@Service
public class ClaudeService2 {

    @Value("${ollama.url}")
    private String ollamaUrl;

    private final ObjectMapper objectMapper = new ObjectMapper();

    public String ask(String question, List<Map<String, Object>> similarCases) throws Exception {
        // 유사 사례 텍스트 조합
        StringBuilder context = new StringBuilder();
        context.append("아래는 유사한 과거 문의 사례입니다:\n\n");
        for (int i = 0; i < similarCases.size(); i++) {
            Map<String, Object> payload = (Map<String, Object>) similarCases.get(i).get("payload");
            context.append("[사례 ").append(i + 1).append("]\n");
            context.append("제목: ").append(payload.get("title")).append("\n");
            context.append("문의내용: ").append(payload.get("report")).append("\n");
            context.append("조치내용: ").append(payload.get("raction")).append("\n");
            context.append("\n");
        }

        String prompt = context.toString() +
                "위 과거 사례를 참고해서 아래 문의에 답변해줘.\n" +
                "과거 사례의 조치내용과 피드백을 최대한 활용하고,\n" +
                "1. 원인 2. 조치방법 형식으로 핵심만 3~5줄 이내로 한국어로 작성해줘:\n\n" +
                "문의: " + question;

        // Ollama LLM 호출
        CloseableHttpClient client = HttpClients.createDefault();
        HttpPost post = new HttpPost(ollamaUrl + "/api/generate");
        post.setHeader("Content-Type", "application/json");

        Map<String, Object> body = new HashMap<>();
        body.put("model", "gemma3:4b");
        body.put("prompt", prompt);
        body.put("stream", false);

        StringEntity entity = new StringEntity(objectMapper.writeValueAsString(body), "UTF-8");
        entity.setContentEncoding("UTF-8");
        entity.setContentType("application/json");
        post.setEntity(entity);

        CloseableHttpResponse response = client.execute(post);
        String responseBody = EntityUtils.toString(response.getEntity(), "UTF-8");
        client.close();

        System.out.println("Claude 응답 Real: " + responseBody);

        JsonNode root = objectMapper.readTree(responseBody);
        return root.get("response").asText();
    }
}