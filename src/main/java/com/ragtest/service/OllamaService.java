package com.ragtest.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.http.client.config.RequestConfig;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.util.EntityUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
public class OllamaService {

    private static final Logger log = LoggerFactory.getLogger(OllamaService.class);

    @Value("${ollama.url}")
    private String ollamaUrl;

    @Value("${ollama.model}")
    private String model;

    @Value("${ollama.timeout-ms:30000}")
    private int timeoutMs;

    private final ObjectMapper objectMapper = new ObjectMapper();

    public List<Double> embed(String text) throws Exception {
        RequestConfig config = RequestConfig.custom()
                .setConnectTimeout(3000)
                .setSocketTimeout(timeoutMs)
                .build();

        try (CloseableHttpClient client = HttpClients.custom().setDefaultRequestConfig(config).build()) {
            HttpPost post = new HttpPost(ollamaUrl + "/api/embeddings");

            Map<String, String> bodyMap = new HashMap<>();
            bodyMap.put("model", model);
            bodyMap.put("prompt", text);

            StringEntity entity = new StringEntity(objectMapper.writeValueAsString(bodyMap), "UTF-8");
            entity.setContentEncoding("UTF-8");
            entity.setContentType("application/json");
            post.setEntity(entity);

            try (CloseableHttpResponse response = client.execute(post)) {
                String responseBody = EntityUtils.toString(response.getEntity(), "UTF-8");
                int status = response.getStatusLine().getStatusCode();
                if (status < 200 || status >= 300) {
                    throw new RuntimeException("Ollama 호출 실패 status=" + status + " body=" + responseBody);
                }
                JsonNode root = objectMapper.readTree(responseBody);
                JsonNode embeddingNode = root.get("embedding");
                if (embeddingNode == null) {
                    throw new RuntimeException("Ollama 응답에 embedding 없음: " + responseBody);
                }

                List<Double> vector = new ArrayList<>(embeddingNode.size());
                for (JsonNode val : embeddingNode) {
                    vector.add(val.asDouble());
                }
                log.debug("Embedded text (length={}, dim={})", text.length(), vector.size());
                return vector;
            }
        }
    }
}
