package com.recallai.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.recallai.dto.KokCallMntrDto;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.client.methods.HttpPut;
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

@Service
public class QdrantService {

    private static final Logger log = LoggerFactory.getLogger(QdrantService.class);

    private static final int VECTOR_DIM = 1024; // bge-m3

    @Value("${qdrant.url}")
    private String qdrantUrl;

    @Value("${qdrant.collection}")
    private String collection;

    @Value("${qdrant.collection-templated:inquiry_templated}")
    private String collectionTemplated;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private CloseableHttpClient httpClient;

    public String getDefaultCollection() { return collection; }
    public String getTemplatedCollection() { return collectionTemplated; }

    /**
     * 앱 시동 시 두 컬렉션(default + templated)이 없으면 자동 생성 + prop_cd 인덱스 보장.
     * Qdrant 미기동 상태에서 앱만 올라가는 경우를 위해 실패해도 앱은 죽지 않음.
     */
    @PostConstruct
    public void init() {
        this.httpClient = HttpClients.createDefault();
        try {
            for (String name : new String[]{collection, collectionTemplated}) {
                if (!collectionExists(name)) {
                    log.info("Qdrant 컬렉션 '{}' 미존재 → 자동 생성 (dim={}, Cosine)", name, VECTOR_DIM);
                    createCollection(name);
                }
                ensurePropCdIndex(name);
                ensureTypeIndex(name);
            }
        } catch (Exception e) {
            log.warn("Qdrant 초기화 스킵 (Qdrant 미기동 가능성): {}", e.getMessage());
        }
    }

    @PreDestroy
    public void destroy() {
        try { if (httpClient != null) httpClient.close(); } catch (Exception ignored) {}
    }

    private boolean collectionExists(String name) throws Exception {
        HttpGet get = new HttpGet(qdrantUrl + "/collections/" + name);
        try (CloseableHttpResponse res = httpClient.execute(get)) {
            return res.getStatusLine().getStatusCode() == 200;
        }
    }

    private void createCollection(String name) throws Exception {
        HttpPut put = new HttpPut(qdrantUrl + "/collections/" + name);
        put.setHeader("Content-Type", "application/json");
        Map<String, Object> vectors = new HashMap<>();
        vectors.put("size", VECTOR_DIM);
        vectors.put("distance", "Cosine");
        Map<String, Object> body = new HashMap<>();
        body.put("vectors", vectors);
        put.setEntity(new ByteArrayEntity(objectMapper.writeValueAsBytes(body)));
        try (CloseableHttpResponse res = httpClient.execute(put)) {
            EntityUtils.consume(res.getEntity());
        }
    }

    private void ensurePropCdIndex(String name) throws Exception {
        HttpPut put = new HttpPut(qdrantUrl + "/collections/" + name + "/index");
        put.setHeader("Content-Type", "application/json");
        Map<String, Object> body = new HashMap<>();
        body.put("field_name", "prop_cd");
        body.put("field_schema", "keyword");
        put.setEntity(new ByteArrayEntity(objectMapper.writeValueAsBytes(body)));
        try (CloseableHttpResponse res = httpClient.execute(put)) {
            EntityUtils.consume(res.getEntity());
        }
    }

    private void ensureTypeIndex(String name) throws Exception {
        HttpPut put = new HttpPut(qdrantUrl + "/collections/" + name + "/index");
        put.setHeader("Content-Type", "application/json");
        Map<String, Object> body = new HashMap<>();
        body.put("field_name", "type");
        body.put("field_schema", "keyword");
        put.setEntity(new ByteArrayEntity(objectMapper.writeValueAsBytes(body)));
        try (CloseableHttpResponse res = httpClient.execute(put)) {
            EntityUtils.consume(res.getEntity());
        }
    }

    /**
     * 기존 포인트에 type=REAL payload 일괄 설정 (재임베딩 없음).
     * FAQ 적재 전 1회 실행. 이미 type 있는 포인트는 덮어씌워지지 않도록 FAQ 제외 필터 사용.
     */
    public void setTypeOnExisting(String collectionName) throws Exception {
        HttpPost post = new HttpPost(qdrantUrl + "/collections/" + collectionName + "/points/payload");
        post.setHeader("Content-Type", "application/json; charset=utf-8");

        Map<String, Object> matchFaq = new HashMap<>();
        matchFaq.put("value", "FAQ");
        Map<String, Object> mustNotClause = new HashMap<>();
        mustNotClause.put("key", "type");
        mustNotClause.put("match", matchFaq);
        Map<String, Object> filter = new HashMap<>();
        filter.put("must_not", Collections.singletonList(mustNotClause));

        Map<String, Object> body = new HashMap<>();
        body.put("payload", Collections.singletonMap("type", "REAL"));
        body.put("filter", filter);

        ByteArrayEntity entity = new ByteArrayEntity(objectMapper.writeValueAsBytes(body));
        entity.setContentType("application/json; charset=utf-8");
        post.setEntity(entity);

        try (CloseableHttpResponse response = httpClient.execute(post)) {
            int status = response.getStatusLine().getStatusCode();
            String resBody = EntityUtils.toString(response.getEntity(), "UTF-8");
            if (status < 200 || status >= 300) {
                throw new RuntimeException("type 일괄 설정 실패 status=" + status + " body=" + resBody);
            }
            log.info("기존 포인트 type=REAL 일괄 설정 완료: {}", resBody);
        }
    }

    /** FAQ 등 범용 포인트 upsert (KokCallMntrDto 없이 payload 직접 지정). */
    public void upsertRawPoint(String collectionName, long id, List<Double> vector,
                               Map<String, Object> payload) throws Exception {
        HttpPut put = new HttpPut(qdrantUrl + "/collections/" + collectionName + "/points?wait=true");
        put.setHeader("Content-Type", "application/json; charset=utf-8");
        put.setHeader("Accept", "application/json");

        Map<String, Object> point = new LinkedHashMap<>();
        point.put("id", id);
        point.put("vector", vector);
        point.put("payload", payload);

        Map<String, Object> body = new HashMap<>();
        body.put("points", Collections.singletonList(point));

        ByteArrayEntity entity = new ByteArrayEntity(objectMapper.writeValueAsBytes(body));
        entity.setContentType("application/json; charset=utf-8");
        put.setEntity(entity);

        try (CloseableHttpResponse response = httpClient.execute(put)) {
            String resBody = EntityUtils.toString(response.getEntity(), "UTF-8");
            int status = response.getStatusLine().getStatusCode();
            if (status < 200 || status >= 300) {
                throw new RuntimeException("Qdrant upsert 실패 id=" + id + " status=" + status + " body=" + resBody);
            }
        }
    }

    /** default 컬렉션(inquiry)에 기본 payload로 저장 — 기존 호출부 호환 */
    public void upsert(Integer id, List<Double> vector, KokCallMntrDto dto) throws Exception {
        upsertTo(collection, id, vector, dto, null);
    }

    /**
     * 컬렉션 지정 + extra payload(HyDE 필드 등) 추가 저장.
     * @param collectionName 대상 컬렉션 이름
     * @param extraPayload   추가 key/value (core_question, situation, cause, solution 등). null 허용.
     */
    public void upsertTo(String collectionName, Integer id, List<Double> vector,
                         KokCallMntrDto dto, Map<String, Object> extraPayload) throws Exception {
        HttpPut put = new HttpPut(qdrantUrl + "/collections/" + collectionName + "/points?wait=true");
        put.setHeader("Content-Type", "application/json; charset=utf-8");
        put.setHeader("Accept", "application/json");

        Map<String, Object> payload = new HashMap<>();
        payload.put("seq_no", dto.getSeqNo());
        payload.put("prop_cd", dto.getPropCd());
        payload.put("cmpx_cd", dto.getCmpxCd());
        // TITLE 제거 (2026-04-20) · Q-Q 매칭 전략상 임베딩·payload 모두 배제
        payload.put("report", KokCallMntrDto.cleanDisplay(dto.getReport()));
        payload.put("feedback", KokCallMntrDto.cleanDisplay(dto.getFeedback()));
        // 접수시스템/접수내용 (CC00010/CC00015) — 필터·통계·UI 표시용
        payload.put("system_cd", dto.getSystemCd());
        payload.put("system_nm", dto.getSystemNm());
        payload.put("system_tp_dtl", dto.getSystemTpDtl());
        payload.put("system_tp_dtl_nm", dto.getSystemTpDtlNm());
        payload.put("type", "REAL");
        if (extraPayload != null) payload.putAll(extraPayload);

        Map<String, Object> point = new LinkedHashMap<>();
        point.put("id", id.longValue());
        point.put("vector", vector);
        point.put("payload", payload);

        Map<String, Object> body = new HashMap<>();
        body.put("points", Collections.singletonList(point));

        byte[] jsonBytes = objectMapper.writeValueAsBytes(body);
        log.debug("Qdrant upsert collection={}, id={}, seq_no={}", collectionName, id, dto.getSeqNo());

        ByteArrayEntity entity = new ByteArrayEntity(jsonBytes);
        entity.setContentType("application/json; charset=utf-8");
        put.setEntity(entity);

        try (CloseableHttpResponse response = httpClient.execute(put)) {
            String responseBody = EntityUtils.toString(response.getEntity(), "UTF-8");
            int status = response.getStatusLine().getStatusCode();
            if (status < 200 || status >= 300) {
                throw new RuntimeException("Qdrant upsert 실패 collection=" + collectionName
                        + " status=" + status + " body=" + responseBody);
            }
        }
    }

    public List<Map<String, Object>> scrollAllPayloads() throws Exception {
        return scrollAllPayloadsOf(collection);
    }

    /** 지정 컬렉션의 seq_no 집합만 빠르게 수집 (중복 적재 방지용). */
    public Set<Integer> collectSeqNos(String collectionName) throws Exception {
        Set<Integer> seqs = new HashSet<>();
        for (Map<String, Object> p : scrollAllPayloadsOf(collectionName)) {
            Map<String, Object> payload = (Map<String, Object>) p.get("payload");
            Object v = payload != null ? payload.get("seq_no") : null;
            if (v instanceof Number) seqs.add(((Number) v).intValue());
        }
        return seqs;
    }

    /**
     * 컬렉션 전체 payload 스크롤 (카테고리 분석·디버그용).
     * 3천 건 규모 가정. 수만 건 이상이면 페이징·샘플링 전략 재검토 필요.
     */
    public List<Map<String, Object>> scrollAllPayloadsOf(String collectionName) throws Exception {
        List<Map<String, Object>> all = new ArrayList<>();
        Object offset = null;
        int batchSize = 1000;

        while (true) {
            HttpPost post = new HttpPost(qdrantUrl + "/collections/" + collectionName + "/points/scroll");
            post.setHeader("Content-Type", "application/json");
            post.setHeader("Accept", "application/json");

            Map<String, Object> body = new HashMap<>();
            body.put("limit", batchSize);
            body.put("with_payload", true);
            body.put("with_vector", false);
            if (offset != null) body.put("offset", offset);

            byte[] jsonBytes = objectMapper.writeValueAsBytes(body);
            ByteArrayEntity entity = new ByteArrayEntity(jsonBytes);
            entity.setContentType("application/json; charset=utf-8");
            post.setEntity(entity);

            try (CloseableHttpResponse response = httpClient.execute(post)) {
                String responseBody = EntityUtils.toString(response.getEntity(), "UTF-8");
                int status = response.getStatusLine().getStatusCode();
                if (status < 200 || status >= 300) {
                    throw new RuntimeException("Qdrant scroll 실패 status=" + status + " body=" + responseBody);
                }
                JsonNode root = objectMapper.readTree(responseBody);
                JsonNode result = root.get("result");
                JsonNode points = result != null ? result.get("points") : null;

                if (points != null) {
                    for (JsonNode point : points) {
                        Map<String, Object> p = new HashMap<>();
                        p.put("id", point.get("id").asLong());
                        p.put("payload", objectMapper.convertValue(point.get("payload"), Map.class));
                        all.add(p);
                    }
                }

                JsonNode nextOffset = result != null ? result.get("next_page_offset") : null;
                if (nextOffset == null || nextOffset.isNull()) break;
                offset = objectMapper.convertValue(nextOffset, Object.class);
            }
        }
        log.info("Qdrant scroll 완료: 총 {}건", all.size());
        return all;
    }

    /** default 컬렉션 검색 — 기존 호출부 호환 */
    public List<Map<String, Object>> search(List<Double> queryVector, int topK, String propCd) throws Exception {
        return searchIn(collection, queryVector, topK, propCd);
    }

    /** type 필터 없이 검색 — 기존 호출부 호환 */
    public List<Map<String, Object>> searchIn(String collectionName, List<Double> queryVector, int topK, String propCd) throws Exception {
        return searchIn(collectionName, queryVector, topK, propCd, null);
    }

    /**
     * 유사 벡터 검색.
     * @param propCd null/빈 문자열이면 호텔 필터 없음 (FAQ 검색 시 null 전달)
     * @param type   "FAQ" | "REAL" | null(필터 없음)
     */
    public List<Map<String, Object>> searchIn(String collectionName, List<Double> queryVector,
                                               int topK, String propCd, String type) throws Exception {
        HttpPost post = new HttpPost(qdrantUrl + "/collections/" + collectionName + "/points/search");
        post.setHeader("Content-Type", "application/json");
        post.setHeader("Accept", "application/json");

        Map<String, Object> body = new HashMap<>();
        body.put("vector", queryVector);
        body.put("limit", topK);
        body.put("with_payload", true);

        List<Map<String, Object>> mustClauses = new ArrayList<>();
        if (propCd != null && !propCd.isEmpty()) {
            Map<String, Object> m = new HashMap<>();
            m.put("key", "prop_cd");
            Map<String, Object> mv = new HashMap<>();
            mv.put("value", propCd);
            m.put("match", mv);
            mustClauses.add(m);
        }
        if (type != null && !type.isEmpty()) {
            Map<String, Object> m = new HashMap<>();
            m.put("key", "type");
            Map<String, Object> mv = new HashMap<>();
            mv.put("value", type);
            m.put("match", mv);
            mustClauses.add(m);
        }
        if (!mustClauses.isEmpty()) {
            Map<String, Object> filter = new HashMap<>();
            filter.put("must", mustClauses);
            body.put("filter", filter);
        }

        byte[] jsonBytes = objectMapper.writeValueAsBytes(body);
        ByteArrayEntity entity = new ByteArrayEntity(jsonBytes);
        entity.setContentType("application/json; charset=utf-8");
        post.setEntity(entity);

        try (CloseableHttpResponse response = httpClient.execute(post)) {
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
