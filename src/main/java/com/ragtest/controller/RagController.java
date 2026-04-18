package com.ragtest.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ragtest.service.RagService;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.util.EntityUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/rag")
public class RagController {

    private static final Logger log = LoggerFactory.getLogger(RagController.class);

    @Autowired
    private RagService ragService;

    @Value("${qdrant.url}")
    private String qdrantUrl;

    @Value("${qdrant.collection}")
    private String qdrantCollection;

    @Value("${rag.reranker.url}")
    private String rerankerUrl;

    @Value("${rag.reranker.enabled:false}")
    private boolean rerankerEnabled;

    @Value("${rag.query-rewrite.enabled:false}")
    private boolean queryRewriteEnabled;

    // MariaDB 데이터 전체 인덱싱 (최초 1회)
    @PostMapping("/index")
    public Map<String, Object> index() {
        Map<String, Object> result = new HashMap<>();
        try {
            String msg = ragService.indexAll();
            result.put("success", true);
            result.put("message", msg);
        } catch (Exception e) {
            log.error("전체 적재 실패", e);
            result.put("success", false);
            result.put("message", e.getMessage());
        }
        return result;
    }

    // 질문 → RAG 답변 (+ 유사 사례 원문)
    @PostMapping("/ask")
    public Map<String, Object> ask(@RequestBody(required = false) Map<String, Object> body) {
        Map<String, Object> result = new HashMap<>();
        long started = System.currentTimeMillis();
        try {
            if (body == null) {
                result.put("success", false);
                result.put("message", "요청 본문(JSON)이 비어있습니다. 예: {\"question\": \"...\"}");
                return result;
            }

            String question = body.get("question") != null ? body.get("question").toString().trim() : "";
            String propCd = body.get("propCd") != null ? body.get("propCd").toString().trim() : null;

            if (question.isEmpty()) {
                result.put("success", false);
                result.put("message", "question은 필수입니다.");
                return result;
            }
            if (question.length() > 2000) {
                result.put("success", false);
                result.put("message", "question은 2000자 이하여야 합니다. (현재: " + question.length() + "자)");
                return result;
            }

            log.info("Received question: {} (propCd={})", question, propCd);

            Map<String, Object> ragResult = ragService.ask(question, propCd);
            result.put("success", true);
            result.put("answer", ragResult.get("answer"));
            result.put("sources", ragResult.get("sources"));
            result.put("elapsed_ms", System.currentTimeMillis() - started);
        } catch (Exception e) {
            log.error("ask 처리 실패", e);
            result.put("success", false);
            result.put("message", e.getMessage());
        }
        return result;
    }

    /** 디버그·튜닝용: LLM 없이 검색 결과만 확인 — 품질 검증용 */
    @PostMapping("/search")
    public Map<String, Object> search(@RequestBody Map<String, Object> body) {
        Map<String, Object> result = new HashMap<>();
        try {
            String question = body.get("question") != null ? body.get("question").toString().trim() : "";
            String propCd = body.get("propCd") != null ? body.get("propCd").toString().trim() : null;
            if (question.isEmpty()) {
                result.put("success", false);
                result.put("message", "question은 필수입니다.");
                return result;
            }
            Map<String, Object> searchResult = ragService.searchOnly(question, propCd);
            result.put("success", true);
            result.putAll(searchResult);
        } catch (Exception e) {
            log.error("search 처리 실패", e);
            result.put("success", false);
            result.put("message", e.getMessage());
        }
        return result;
    }

    @PostMapping("/index/updated")
    public Map<String, Object> indexUpdated() {
        Map<String, Object> result = new HashMap<>();
        try {
            String msg = ragService.indexUpdated();
            result.put("success", true);
            result.put("message", msg);
        } catch (Exception e) {
            log.error("증분 적재 실패", e);
            result.put("success", false);
            result.put("message", e.getMessage());
        }
        return result;
    }

    /** 시스템 상태 조회 (Qdrant 건수, Reranker 가용성, 플래그) */
    @GetMapping("/status")
    public Map<String, Object> status() {
        Map<String, Object> result = new HashMap<>();
        result.put("reranker_enabled", rerankerEnabled);
        result.put("query_rewrite_enabled", queryRewriteEnabled);
        result.put("qdrant", fetchQdrantStatus());
        result.put("reranker", fetchRerankerStatus());
        return result;
    }

    private Map<String, Object> fetchQdrantStatus() {
        Map<String, Object> s = new HashMap<>();
        try (CloseableHttpClient client = HttpClients.createDefault()) {
            HttpGet get = new HttpGet(qdrantUrl + "/collections/" + qdrantCollection);
            try (CloseableHttpResponse res = client.execute(get)) {
                String body = EntityUtils.toString(res.getEntity(), "UTF-8");
                ObjectMapper m = new ObjectMapper();
                Map<String, Object> parsed = m.readValue(body, Map.class);
                Map<String, Object> r = (Map<String, Object>) parsed.get("result");
                s.put("available", r != null);
                if (r != null) {
                    s.put("points_count", r.get("points_count"));
                    s.put("status", r.get("status"));
                }
            }
        } catch (Exception e) {
            s.put("available", false);
            s.put("error", e.getMessage());
        }
        return s;
    }

    private Map<String, Object> fetchRerankerStatus() {
        Map<String, Object> s = new HashMap<>();
        try (CloseableHttpClient client = HttpClients.createDefault()) {
            HttpGet get = new HttpGet(rerankerUrl + "/health");
            try (CloseableHttpResponse res = client.execute(get)) {
                s.put("available", res.getStatusLine().getStatusCode() == 200);
                s.put("body", EntityUtils.toString(res.getEntity(), "UTF-8"));
            }
        } catch (Exception e) {
            s.put("available", false);
            s.put("error", e.getMessage());
        }
        return s;
    }
}
