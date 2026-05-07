package com.recallai.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.recallai.dto.KokCallMntrDto;
import com.recallai.repository.KokCallMntrMapper;
import com.recallai.service.HotelCacheService;
import com.recallai.service.IndexFaqService;
import com.recallai.service.PhoneLookupService;
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

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/rag")
public class RagController {

    private static final Logger log = LoggerFactory.getLogger(RagController.class);
    private static final ObjectMapper STATUS_PARSER = new ObjectMapper();

    @Autowired private RagService ragService;
    @Autowired private IndexFaqService indexFaqService;
    @Autowired private HotelCacheService hotelCacheService;
    @Autowired private PhoneLookupService phoneLookupService;
    @Autowired private KokCallMntrMapper mapper;

    @Value("${qdrant.url}")             private String qdrantUrl;
    @Value("${qdrant.collection}")      private String qdrantCollection;
    @Value("${rag.reranker.url}")       private String rerankerUrl;
    @Value("${rag.reranker.enabled:false}")     private boolean rerankerEnabled;
    @Value("${rag.query-rewrite.enabled:false}") private boolean queryRewriteEnabled;

    // ─── 공통 응답 빌더 ─────────────────────────────────────────
    @FunctionalInterface
    private interface RagOp { void run(Map<String, Object> result) throws Exception; }

    /** try-catch + success/message 보일러플레이트 묶음. op 안에서 result에 키만 채우면 됨. */
    private Map<String, Object> safeOp(String errorLog, RagOp op) {
        Map<String, Object> result = new HashMap<>();
        try {
            op.run(result);
            result.putIfAbsent("success", true);
        } catch (Exception e) {
            log.error(errorLog, e);
            result.put("success", false);
            result.put("message", e.getMessage());
        }
        return result;
    }

    // ─── 인덱싱 ─────────────────────────────────────────────────

    /** MariaDB 데이터 전체 인덱싱 (최초 1회). */
    @PostMapping("/index")
    public Map<String, Object> index() {
        return safeOp("전체 적재 실패",
                r -> r.put("message", ragService.indexAll()));
    }

    /** HyDE 하이브리드 템플릿 적재 (inquiry_templated 컬렉션). limit=0이면 전체. */
    @PostMapping("/index/templated")
    public Map<String, Object> indexTemplated(@RequestParam(defaultValue = "0") int limit) {
        return safeOp("템플릿 적재 실패",
                r -> r.put("message", ragService.indexAllTemplated(limit)));
    }

    @PostMapping("/index/updated")
    public Map<String, Object> indexUpdated() {
        return safeOp("증분 적재 실패",
                r -> r.put("message", ragService.indexUpdated()));
    }

    /** 단건 즉시 인덱싱 — KOK_CALL_MNTR 저장 직후 호출. */
    @PostMapping("/index/single/{seqNo}")
    public Map<String, Object> indexSingle(@PathVariable Integer seqNo) {
        return safeOp("단건 인덱싱 실패 seq_no=" + seqNo, r -> {
            List<KokCallMntrDto> list = mapper.selectBySeqNos(Collections.singletonList(seqNo));
            if (list.isEmpty()) {
                r.put("success", false);
                r.put("message", "seq_no=" + seqNo + " 레코드를 찾을 수 없습니다.");
                return;
            }
            ragService.indexSingle(list.get(0));
            r.put("message", "단건 인덱싱 완료 seq_no=" + seqNo);
        });
    }

    /** 실패 장부 조회 (failed_index.txt). */
    @GetMapping("/index/failed")
    public Map<String, Object> failedList() {
        return safeOp("실패 조회 실패",
                r -> r.putAll(ragService.failedSummary()));
    }

    /** FAQ (faq.json) Qdrant 적재. */
    @PostMapping("/index/faq")
    public Map<String, Object> indexFaq() {
        return safeOp("FAQ 적재 실패",
                r -> r.put("message", indexFaqService.indexFaq()));
    }

    /** 기존 REAL 포인트에 type=REAL payload 일괄 설정 (재임베딩 없음). */
    @PostMapping("/index/set-types")
    public Map<String, Object> setTypes() {
        return safeOp("type 설정 실패",
                r -> r.put("message", indexFaqService.setTypesOnExisting()));
    }

    /** 실패 장부 재시도. 성공 건은 장부에서 제거. */
    @PostMapping("/index/retry-failed")
    public Map<String, Object> retryFailed() {
        return safeOp("재시도 실패",
                r -> r.putAll(ragService.retryFailed()));
    }

    // ─── 질문/검색 ─────────────────────────────────────────────

    /** 질문 → RAG 답변. mode: "default" | "templated" — A/B 비교용 컬렉션 선택. */
    @PostMapping("/ask")
    public Map<String, Object> ask(@RequestBody(required = false) Map<String, Object> body) {
        long started = System.currentTimeMillis();
        return safeOp("ask 처리 실패", r -> {
            if (body == null) {
                r.put("success", false);
                r.put("message", "요청 본문(JSON)이 비어있습니다. 예: {\"question\": \"...\"}");
                return;
            }
            String question = trimStr(body, "question");
            String propCd   = trimStr(body, "propCd");
            String mode     = trimStr(body, "mode");

            if (question.isEmpty()) {
                r.put("success", false);
                r.put("message", "question은 필수입니다.");
                return;
            }
            if (question.length() > 2000) {
                r.put("success", false);
                r.put("message", "question은 2000자 이하여야 합니다. (현재: " + question.length() + "자)");
                return;
            }

            log.info("Received question: {} (propCd={}, mode={})", question, propCd, mode);
            Map<String, Object> ragResult = ragService.ask(question, propCd, mode);
            r.put("mode", mode != null && !mode.isEmpty() ? mode : "default");
            r.put("answer",  ragResult.get("answer"));
            r.put("faq",     ragResult.get("faq"));
            r.put("sources", ragResult.get("sources"));
            r.put("elapsed_ms", System.currentTimeMillis() - started);
        });
    }

    /** 디버그·튜닝용: LLM 없이 검색 결과만 확인. */
    @PostMapping("/search")
    public Map<String, Object> search(@RequestBody Map<String, Object> body) {
        return safeOp("search 처리 실패", r -> {
            String question = trimStr(body, "question");
            String propCd   = trimStr(body, "propCd");
            String mode     = trimStr(body, "mode");
            if (question.isEmpty()) {
                r.put("success", false);
                r.put("message", "question은 필수입니다.");
                return;
            }
            r.putAll(ragService.searchOnly(question, propCd, mode));
        });
    }

    /**
     * Qdrant 적재분에서 랜덤 샘플링 → Groq에 카테고리 체계 제안받기.
     * 요청 바디(옵션): {"sampleSize": 150}  — 범위 10~300, 기본 150.
     */
    @PostMapping("/analyze-categories")
    public Map<String, Object> analyzeCategories(@RequestBody(required = false) Map<String, Object> body) {
        long started = System.currentTimeMillis();
        Map<String, Object> result = safeOp("카테고리 분석 실패", r -> {
            int sampleSize = 150;
            if (body != null && body.get("sampleSize") != null) {
                Object v = body.get("sampleSize");
                sampleSize = (v instanceof Number) ? ((Number) v).intValue()
                                                   : Integer.parseInt(v.toString().trim());
            }
            sampleSize = Math.max(10, Math.min(300, sampleSize));
            r.putAll(ragService.analyzeCategories(sampleSize));
        });
        result.put("elapsed_ms", System.currentTimeMillis() - started);
        return result;
    }

    // ─── 호텔/전화번호 캐시 관리 ─────────────────────────────────

    /**
     * 신규 호텔 추가 — PMS 통화업무등록 시 hotels.json에 없는 호텔 자동 등록.
     * body: { propCd, propShrtNm, propFullNm(선택), cmpxCd(선택), cmpxNm(선택), cmpxReprTel(선택) }
     */
    @PostMapping("/hotels/add")
    public Map<String, Object> addHotel(@RequestBody Map<String, Object> body) {
        return safeOp("호텔 추가 실패", r -> {
            boolean added = hotelCacheService.addHotelIfAbsent(
                    str(body, "propCd"),  str(body, "propShrtNm"), str(body, "propFullNm"),
                    str(body, "cmpxCd"),  str(body, "cmpxNm"),     str(body, "cmpxReprTel"));
            r.put("added", added);
        });
    }

    /** 전화번호 → propCd 매핑 추가. */
    @PostMapping("/phone-lookup/add")
    public Map<String, Object> addPhoneLookup(@RequestBody Map<String, Object> body) {
        return safeOp("phone_lookup 추가 실패", r -> {
            boolean added = phoneLookupService.addIfAbsent(str(body, "phoneNo"), str(body, "propCd"));
            r.put("added", added);
        });
    }

    /** 호텔 정보 캐시 새로고침 (DB 재조회 + hotels.json 재저장). */
    @PostMapping("/hotels/refresh")
    public Map<String, Object> refreshHotels() {
        return safeOp("호텔 캐시 새로고침 실패", r -> {
            hotelCacheService.refresh();
            r.put("count", hotelCacheService.getAllHotels().size());
        });
    }

    // ─── 상태 ────────────────────────────────────────────────────

    /** 시스템 상태 (Qdrant 건수, Reranker 가용성, 플래그). */
    @GetMapping("/status")
    public Map<String, Object> status() {
        Map<String, Object> result = new HashMap<>();
        result.put("reranker_enabled", rerankerEnabled);
        result.put("query_rewrite_enabled", queryRewriteEnabled);
        result.put("qdrant", fetchQdrantStatus());
        result.put("reranker", fetchRerankerStatus());
        return result;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> fetchQdrantStatus() {
        Map<String, Object> s = new HashMap<>();
        try (CloseableHttpClient client = HttpClients.createDefault();
             CloseableHttpResponse res = client.execute(new HttpGet(qdrantUrl + "/collections/" + qdrantCollection))) {
            String body = EntityUtils.toString(res.getEntity(), "UTF-8");
            Map<String, Object> parsed = STATUS_PARSER.readValue(body, Map.class);
            Map<String, Object> r = (Map<String, Object>) parsed.get("result");
            s.put("available", r != null);
            if (r != null) {
                s.put("points_count", r.get("points_count"));
                s.put("status",       r.get("status"));
            }
        } catch (Exception e) {
            s.put("available", false);
            s.put("error", e.getMessage());
        }
        return s;
    }

    private Map<String, Object> fetchRerankerStatus() {
        Map<String, Object> s = new HashMap<>();
        try (CloseableHttpClient client = HttpClients.createDefault();
             CloseableHttpResponse res = client.execute(new HttpGet(rerankerUrl + "/health"))) {
            s.put("available", res.getStatusLine().getStatusCode() == 200);
            s.put("body", EntityUtils.toString(res.getEntity(), "UTF-8"));
        } catch (Exception e) {
            s.put("available", false);
            s.put("error", e.getMessage());
        }
        return s;
    }

    // ─── 헬퍼 ────────────────────────────────────────────────────

    private static String str(Map<String, Object> m, String key) {
        Object v = m.get(key);
        return v != null ? v.toString() : null;
    }

    private static String trimStr(Map<String, Object> m, String key) {
        Object v = m == null ? null : m.get(key);
        return v != null ? v.toString().trim() : "";
    }
}
