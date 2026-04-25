package com.recallai.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.recallai.service.HotelCacheService;
import com.recallai.service.IndexFaqService;
import com.recallai.service.RagService;
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

    @Autowired
    private IndexFaqService indexFaqService;

    @Autowired
    private HotelCacheService hotelCacheService;

    @Autowired
    private com.recallai.repository.KokCallMntrMapper mapper;

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
    // mode: "default" | "templated" — A/B 비교용 컬렉션 선택
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
            String mode = body.get("mode") != null ? body.get("mode").toString().trim() : null;

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

            log.info("Received question: {} (propCd={}, mode={})", question, propCd, mode);

            Map<String, Object> ragResult = ragService.ask(question, propCd, mode);
            result.put("success", true);
            result.put("mode", mode != null ? mode : "default");
            result.put("answer", ragResult.get("answer"));
            result.put("faq", ragResult.get("faq"));
            result.put("sources", ragResult.get("sources"));
            result.put("elapsed_ms", System.currentTimeMillis() - started);
        } catch (Exception e) {
            log.error("ask 처리 실패", e);
            result.put("success", false);
            result.put("message", e.getMessage());
        }
        return result;
    }

    /** HyDE 하이브리드 템플릿 적재 (inquiry_templated 컬렉션). */
    @PostMapping("/index/templated")
    public Map<String, Object> indexTemplated() {
        Map<String, Object> result = new HashMap<>();
        try {
            String msg = ragService.indexAllTemplated();
            result.put("success", true);
            result.put("message", msg);
        } catch (Exception e) {
            log.error("템플릿 적재 실패", e);
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
            String mode = body.get("mode") != null ? body.get("mode").toString().trim() : null;
            if (question.isEmpty()) {
                result.put("success", false);
                result.put("message", "question은 필수입니다.");
                return result;
            }
            Map<String, Object> searchResult = ragService.searchOnly(question, propCd, mode);
            result.put("success", true);
            result.putAll(searchResult);
        } catch (Exception e) {
            log.error("search 처리 실패", e);
            result.put("success", false);
            result.put("message", e.getMessage());
        }
        return result;
    }

    /**
     * Qdrant 적재분에서 랜덤 샘플링 → Groq에 "이 문의들 어떻게 분류될까" 던져 카테고리 체계 제안받기.
     * 요청 바디(옵션): {"sampleSize": 150}  — 범위 10~300, 기본 150
     */
    @PostMapping("/analyze-categories")
    public Map<String, Object> analyzeCategories(@RequestBody(required = false) Map<String, Object> body) {
        Map<String, Object> result = new HashMap<>();
        long started = System.currentTimeMillis();
        try {
            int sampleSize = 150;
            if (body != null && body.get("sampleSize") != null) {
                Object v = body.get("sampleSize");
                if (v instanceof Number) sampleSize = ((Number) v).intValue();
                else sampleSize = Integer.parseInt(v.toString().trim());
            }
            if (sampleSize < 10) sampleSize = 10;
            if (sampleSize > 300) sampleSize = 300;

            Map<String, Object> r = ragService.analyzeCategories(sampleSize);
            result.put("success", true);
            result.putAll(r);
            result.put("elapsed_ms", System.currentTimeMillis() - started);
        } catch (Exception e) {
            log.error("카테고리 분석 실패", e);
            result.put("success", false);
            result.put("message", e.getMessage());
            result.put("elapsed_ms", System.currentTimeMillis() - started);
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

    /** 단건 즉시 인덱싱 — KOK_CALL_MNTR 저장 직후 호출. seqNo로 레코드 조회 후 Qdrant upsert. */
    @PostMapping("/index/single/{seqNo}")
    public Map<String, Object> indexSingle(@PathVariable Integer seqNo) {
        Map<String, Object> result = new HashMap<>();
        try {
            java.util.List<com.recallai.dto.KokCallMntrDto> list =
                    mapper.selectBySeqNos(java.util.Collections.singletonList(seqNo));
            if (list.isEmpty()) {
                result.put("success", false);
                result.put("message", "seq_no=" + seqNo + " 레코드를 찾을 수 없습니다.");
                return result;
            }
            ragService.indexSingle(list.get(0));
            result.put("success", true);
            result.put("message", "단건 인덱싱 완료 seq_no=" + seqNo);
        } catch (Exception e) {
            log.error("단건 인덱싱 실패 seq_no={}", seqNo, e);
            result.put("success", false);
            result.put("message", e.getMessage());
        }
        return result;
    }

    /** 실패 장부 조회 (failed_index.txt). 개수·사유별 집계·샘플 10건. */
    @GetMapping("/index/failed")
    public Map<String, Object> failedList() {
        Map<String, Object> result = new HashMap<>();
        try {
            result.put("success", true);
            result.putAll(ragService.failedSummary());
        } catch (Exception e) {
            log.error("실패 조회 실패", e);
            result.put("success", false);
            result.put("message", e.getMessage());
        }
        return result;
    }

    /** FAQ (faq.json) Qdrant 적재 — type=FAQ로 upsert */
    @PostMapping("/index/faq")
    public Map<String, Object> indexFaq() {
        Map<String, Object> result = new HashMap<>();
        try {
            String msg = indexFaqService.indexFaq();
            result.put("success", true);
            result.put("message", msg);
        } catch (Exception e) {
            log.error("FAQ 적재 실패", e);
            result.put("success", false);
            result.put("message", e.getMessage());
        }
        return result;
    }

    /** 기존 REAL 포인트에 type=REAL payload 일괄 설정 (재임베딩 없음) */
    @PostMapping("/index/set-types")
    public Map<String, Object> setTypes() {
        Map<String, Object> result = new HashMap<>();
        try {
            String msg = indexFaqService.setTypesOnExisting();
            result.put("success", true);
            result.put("message", msg);
        } catch (Exception e) {
            log.error("type 설정 실패", e);
            result.put("success", false);
            result.put("message", e.getMessage());
        }
        return result;
    }

    /** 실패 장부에 있는 seq_no들을 DB에서 재조회해 다시 적재. 성공 건은 장부에서 제거. */
    @PostMapping("/index/retry-failed")
    public Map<String, Object> retryFailed() {
        Map<String, Object> result = new HashMap<>();
        try {
            result.putAll(ragService.retryFailed());
        } catch (Exception e) {
            log.error("재시도 실패", e);
            result.put("success", false);
            result.put("message", e.getMessage());
        }
        return result;
    }

    /** 호텔 정보 캐시 새로고침 (DB 재조회 + hotels.json 재저장) */
    @PostMapping("/hotels/refresh")
    public Map<String, Object> refreshHotels() {
        Map<String, Object> result = new HashMap<>();
        try {
            hotelCacheService.refresh();
            result.put("success", true);
            result.put("count", hotelCacheService.getAllHotels().size());
        } catch (Exception e) {
            log.error("호텔 캐시 새로고침 실패", e);
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
