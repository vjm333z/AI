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

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
public class OllamaService {

    @Value("${ollama.url}")
    private String ollamaUrl;

    @Value("${ollama.model}")
    private String model;

    private final ObjectMapper objectMapper = new ObjectMapper();
    public List<Double> embed(String text) throws Exception {
        CloseableHttpClient client = HttpClients.createDefault();
        HttpPost post = new HttpPost(ollamaUrl + "/api/embeddings");

        Map<String, String> bodyMap = new HashMap<>();
        bodyMap.put("model", model);
        bodyMap.put("prompt", text);

        StringEntity entity = new StringEntity(objectMapper.writeValueAsString(bodyMap), "UTF-8");
        entity.setContentEncoding("UTF-8");
        entity.setContentType("application/json");
        post.setEntity(entity);

        CloseableHttpResponse response = client.execute(post);
        String responseBody = EntityUtils.toString(response.getEntity(), "UTF-8");
        client.close();

        System.out.println("Ollama 임베딩 응답: " + responseBody);

        JsonNode root = objectMapper.readTree(responseBody);
        JsonNode embeddingNode = root.get("embedding");

        List<Double> vector = new ArrayList<>();
        for (JsonNode val : embeddingNode) {
            vector.add(val.asDouble());
        }
        return vector;
    }
}
