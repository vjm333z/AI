package com.ragtest.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ragtest.dto.KokCallMntrDto;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.client.methods.HttpPut;
import org.apache.http.entity.ByteArrayEntity;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.util.EntityUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.*;

@Service
public class QdrantService {

    @Value("${qdrant.url}")
    private String qdrantUrl;

    @Value("${qdrant.collection}")
    private String collection;

    private final ObjectMapper objectMapper = new ObjectMapper();

    // 벡터 저장
    public void upsert(Integer id, List<Double> vector, KokCallMntrDto dto) throws Exception {
        CloseableHttpClient client = HttpClients.createDefault();
        HttpPut put = new HttpPut(qdrantUrl + "/collections/" + collection + "/points?wait=true");
        put.setHeader("Content-Type", "application/json; charset=utf-8");
        put.setHeader("Accept", "application/json");

        Map<String, Object> payload = new HashMap<>();
        payload.put("seq_no", dto.getSeqNo());
        payload.put("title", dto.getTitle());
        payload.put("report", dto.getReport());
        payload.put("feedback", dto.getFeedback());

        Map<String, Object> point = new LinkedHashMap<>();
        point.put("id", id.longValue());
        point.put("vector", vector);
        point.put("payload", payload);

        Map<String, Object> body = new HashMap<>();
        body.put("points", Collections.singletonList(point));

        byte[] jsonBytes = objectMapper.writeValueAsBytes(body);
        System.out.println("전송 JSON: " + new String(jsonBytes, "UTF-8"));  // 추가

        ByteArrayEntity entity = new ByteArrayEntity(jsonBytes);
        entity.setContentType("application/json; charset=utf-8");
        put.setEntity(entity);

        CloseableHttpResponse response = client.execute(put);
        String responseBody = EntityUtils.toString(response.getEntity(), "UTF-8");
        client.close();

        System.out.println("Qdrant 저장 응답: " + responseBody);
    }

    // 유사 벡터 검색
    public List<Map<String, Object>> search(List<Double> queryVector, int topK) throws Exception {
        CloseableHttpClient client = HttpClients.createDefault();
        HttpPost post = new HttpPost(qdrantUrl + "/collections/" + collection + "/points/search");
        post.setHeader("Content-Type", "application/json");
        post.setHeader("Accept", "application/json");

        Map<String, Object> body = new HashMap<>();
        body.put("vector", queryVector);
        body.put("limit", topK);
        body.put("with_payload", true);

        byte[] jsonBytes = objectMapper.writeValueAsBytes(body);
        ByteArrayEntity entity = new ByteArrayEntity(jsonBytes);
        entity.setContentType("application/json; charset=utf-8");
        post.setEntity(entity);

        CloseableHttpResponse response = client.execute(post);
        String responseBody = EntityUtils.toString(response.getEntity(), "UTF-8");
        client.close();

        JsonNode root = objectMapper.readTree(responseBody);
        JsonNode results = root.get("result");

        List<Map<String, Object>> list = new ArrayList<>();
        if (results != null) {
            for (JsonNode item : results) {
                Map<String, Object> map = new HashMap<>();
                map.put("score", item.get("score").asDouble());
                map.put("payload", objectMapper.convertValue(item.get("payload"), Map.class));
                list.add(map);
            }
        }
        return list;
    }
}
