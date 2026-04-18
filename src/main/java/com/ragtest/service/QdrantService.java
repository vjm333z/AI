package com.ragtest.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ragtest.dto.KokCallMntrDto;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.client.methods.HttpPut;
import org.apache.http.entity.ByteArrayEntity;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.util.EntityUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.annotation.PostConstruct;
import java.util.*;

@Service
public class QdrantService {

    private static final Logger log = LoggerFactory.getLogger(QdrantService.class);

    private static final int VECTOR_DIM = 1024; // bge-m3

    @Value("${qdrant.url}")
    private String qdrantUrl;

    @Value("${qdrant.collection}")
    private String collection;

    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * 앱 시동 시 컬렉션이 없으면 자동 생성 + prop_cd 필드 인덱스 보장.
     * Qdrant 미기동 상태에서 앱만 올라가는 경우를 위해 실패해도 앱은 죽지 않음.
     */
    @PostConstruct
    public void init() {
        try {
            if (!collectionExists()) {
                log.info("Qdrant 컬렉션 '{}' 미존재 → 자동 생성 (dim={}, Cosine)", collection, VECTOR_DIM);
                createCollection();
            }
            ensurePropCdIndex();
        } catch (Exception e) {
            log.warn("Qdrant 초기화 스킵 (Qdrant 미기동 가능성): {}", e.getMessage());
        }
    }

    private boolean collectionExists() throws Exception {
        try (CloseableHttpClient client = HttpClients.createDefault()) {
            HttpGet get = new HttpGet(qdrantUrl + "/collections/" + collection);
            try (CloseableHttpResponse res = client.execute(get)) {
                return res.getStatusLine().getStatusCode() == 200;
            }
        }
    }

    private void createCollection() throws Exception {
        try (CloseableHttpClient client = HttpClients.createDefault()) {
            HttpPut put = new HttpPut(qdrantUrl + "/collections/" + collection);
            put.setHeader("Content-Type", "application/json");
            Map<String, Object> vectors = new HashMap<>();
            vectors.put("size", VECTOR_DIM);
            vectors.put("distance", "Cosine");
            Map<String, Object> body = new HashMap<>();
            body.put("vectors", vectors);
            put.setEntity(new ByteArrayEntity(objectMapper.writeValueAsBytes(body)));
            try (CloseableHttpResponse res = client.execute(put)) {
                EntityUtils.consume(res.getEntity());
            }
        }
    }

    /** prop_cd 필드에 keyword 인덱스 생성 (필터 성능 향상, 중복 호출 안전) */
    private void ensurePropCdIndex() throws Exception {
        try (CloseableHttpClient client = HttpClients.createDefault()) {
            HttpPut put = new HttpPut(qdrantUrl + "/collections/" + collection + "/index");
            put.setHeader("Content-Type", "application/json");
            Map<String, Object> body = new HashMap<>();
            body.put("field_name", "prop_cd");
            body.put("field_schema", "keyword");
            put.setEntity(new ByteArrayEntity(objectMapper.writeValueAsBytes(body)));
            try (CloseableHttpResponse res = client.execute(put)) {
                EntityUtils.consume(res.getEntity());
            }
        }
    }

    // 벡터 저장
    public void upsert(Integer id, List<Double> vector, KokCallMntrDto dto) throws Exception {
        HttpPut put = new HttpPut(qdrantUrl + "/collections/" + collection + "/points?wait=true");
        put.setHeader("Content-Type", "application/json; charset=utf-8");
        put.setHeader("Accept", "application/json");

        Map<String, Object> payload = new HashMap<>();
        payload.put("seq_no", dto.getSeqNo());
        payload.put("prop_cd", dto.getPropCd());
        payload.put("title", KokCallMntrDto.cleanDisplay(dto.getTitle()));
        payload.put("report", KokCallMntrDto.cleanDisplay(dto.getReport()));
        payload.put("feedback", KokCallMntrDto.cleanDisplay(dto.getFeedback()));

        Map<String, Object> point = new LinkedHashMap<>();
        point.put("id", id.longValue());
        point.put("vector", vector);
        point.put("payload", payload);

        Map<String, Object> body = new HashMap<>();
        body.put("points", Collections.singletonList(point));

        byte[] jsonBytes = objectMapper.writeValueAsBytes(body);
        log.debug("Qdrant upsert id={}, seq_no={}", id, dto.getSeqNo());

        ByteArrayEntity entity = new ByteArrayEntity(jsonBytes);
        entity.setContentType("application/json; charset=utf-8");
        put.setEntity(entity);

        try (CloseableHttpClient client = HttpClients.createDefault();
             CloseableHttpResponse response = client.execute(put)) {
            String responseBody = EntityUtils.toString(response.getEntity(), "UTF-8");
            int status = response.getStatusLine().getStatusCode();
            if (status < 200 || status >= 300) {
                throw new RuntimeException("Qdrant upsert 실패 status=" + status + " body=" + responseBody);
            }
            log.debug("Qdrant upsert response: {}", responseBody);
        }
    }

    // 유사 벡터 검색 (propCd null이면 필터 없음)
    public List<Map<String, Object>> search(List<Double> queryVector, int topK, String propCd) throws Exception {
        HttpPost post = new HttpPost(qdrantUrl + "/collections/" + collection + "/points/search");
        post.setHeader("Content-Type", "application/json");
        post.setHeader("Accept", "application/json");

        Map<String, Object> body = new HashMap<>();
        body.put("vector", queryVector);
        body.put("limit", topK);
        body.put("with_payload", true);

        if (propCd != null && !propCd.isEmpty()) {
            Map<String, Object> matchValue = new HashMap<>();
            matchValue.put("value", propCd);
            Map<String, Object> mustClause = new HashMap<>();
            mustClause.put("key", "prop_cd");
            mustClause.put("match", matchValue);
            Map<String, Object> filter = new HashMap<>();
            filter.put("must", Collections.singletonList(mustClause));
            body.put("filter", filter);
        }

        byte[] jsonBytes = objectMapper.writeValueAsBytes(body);
        ByteArrayEntity entity = new ByteArrayEntity(jsonBytes);
        entity.setContentType("application/json; charset=utf-8");
        post.setEntity(entity);

        try (CloseableHttpClient client = HttpClients.createDefault();
             CloseableHttpResponse response = client.execute(post)) {
            String responseBody = EntityUtils.toString(response.getEntity(), "UTF-8");
            int status = response.getStatusLine().getStatusCode();
            if (status < 200 || status >= 300) {
                throw new RuntimeException("Qdrant search 실패 status=" + status + " body=" + responseBody);
            }

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
}
