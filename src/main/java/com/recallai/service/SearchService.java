package com.recallai.service;

import com.recallai.config.RagProperties;
import com.recallai.model.PointType;
import com.recallai.model.SearchMode;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * RAG 검색·답변·분석 파이프라인.
 * - ask(): 검색 → (옵션)Reranker → MMR → LLM 답변 (Groq 우선, 실패 시 OpenAI 폴백)
 * - searchOnly(): 디버그·튜닝용 (LLM 미호출)
 * - analyzeCategories(): Qdrant 샘플링 → Groq 카테고리 제안 (개발 도구)
 */
@Service
@RequiredArgsConstructor
public class SearchService {

    private static final Logger log = LoggerFactory.getLogger(SearchService.class);

    private final OllamaService ollamaService;
    private final QdrantService qdrantService;
    private final GroqService groqService;
    private final OpenAiService openAiService;
    private final RerankerService rerankerService;
    private final QueryRewriteService queryRewriteService;
    private final RagProperties props;

    public Map<String, Object> ask(String question, String propCd) throws Exception {
        return ask(question, propCd, null);
    }

    /**
     * 질문 → (옵션)Query Rewrite → 임베딩 → Qdrant 검색 → (옵션)Reranker + MMR → Groq(폴백 OpenAI) 답변.
     * propCd null/빈 문자열이면 호텔 필터 없음. mode는 null/"default" 또는 "templated".
     */
    public Map<String, Object> ask(String question, String propCd, String mode) throws Exception {
        String collectionName = resolveCollection(mode);

        // 1. (옵션) 질문 확장 — 검색 품질 향상
        String searchQuery = props.getQueryRewrite().isEnabled() ? queryRewriteService.rewrite(question) : question;

        // 2. 질문 임베딩
        List<Double> queryVector = ollamaService.embed(searchQuery);

        // 3-FAQ. FAQ 검색 (type=FAQ 필터, propCd 무관, reranker 미적용)
        List<Map<String, Object>> faqRaw = qdrantService.searchIn(collectionName, queryVector,
                props.getSearch().getFaqTopK(), null, PointType.FAQ.name());
        List<Map<String, Object>> faqResults = faqRaw.stream()
                .filter(c -> c.get("score") instanceof Number n && n.doubleValue() >= props.getSearch().getFaqScoreThreshold())
                .toList();
        log.info("FAQ 검색 결과: {}건 (threshold 후: {}건)", faqRaw.size(), faqResults.size());

        // 3. Qdrant에서 REAL Top N 검색 (reranker가 있으면 넉넉히, 없으면 final-top-k만)
        int fetchK = props.getReranker().isEnabled() ? props.getSearch().getTopK() : props.getSearch().getFinalTopK();
        List<Map<String, Object>> similarCases = qdrantService.searchIn(collectionName, queryVector, fetchK, propCd, PointType.REAL.name());

        // 4. 유사도 점수 필터
        List<Map<String, Object>> filteredCases = similarCases.stream()
                .filter(c -> c.get("score") instanceof Number n && n.doubleValue() >= props.getSearch().getScoreThreshold())
                .toList();

        log.info("유사 사례 검색 결과: {}건", similarCases.size());
        log.info("필터 후 사례: {}건", filteredCases.size());
        for (Map<String, Object> c : filteredCases) {
            Map<String, Object> payload = (Map<String, Object>) c.get("payload");
            log.info("유사사례 점수: {}", c.get("score"));
            if (payload != null) {
                log.info("유사사례 내용: {}", payload.get("report"));
                log.info("유사사례 답변: {}", payload.get("feedback"));
            }
            log.info("---");
        }

        // 5. (옵션) Reranker + MMR
        List<Map<String, Object>> finalCases = rankAndDiversify(question, filteredCases);

        // 6. LLM 답변 생성 — Groq 우선, 실패 시 OpenAI 폴백.
        String answer;
        try {
            answer = groqService.ask(question, finalCases);
            log.info("Groq 답변 생성 완료");
        } catch (Exception e) {
            log.warn("Groq 답변 실패, OpenAI 폴백: {}", e.getMessage());
            answer = openAiService.ask(question, finalCases);
        }

        // 7. 응답 구성
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("answer", answer);
        response.put("faq", toFaqSources(faqResults));
        response.put("sources", toSources(finalCases));
        return response;
    }

    public Map<String, Object> searchOnly(String question, String propCd) throws Exception {
        return searchOnly(question, propCd, null);
    }

    /** 디버그·튜닝용: LLM 거치지 않고 Qdrant + (옵션) Reranker 결과만 반환 */
    public Map<String, Object> searchOnly(String question, String propCd, String mode) throws Exception {
        String collectionName = resolveCollection(mode);
        String searchQuery = props.getQueryRewrite().isEnabled() ? queryRewriteService.rewrite(question) : question;
        List<Double> queryVector = ollamaService.embed(searchQuery);
        int fetchK = props.getReranker().isEnabled() ? props.getSearch().getTopK() : props.getSearch().getFinalTopK();
        List<Map<String, Object>> similarCases = qdrantService.searchIn(collectionName, queryVector, fetchK, propCd, PointType.REAL.name());
        List<Map<String, Object>> filteredCases = similarCases.stream()
                .filter(c -> c.get("score") instanceof Number n && n.doubleValue() >= props.getSearch().getScoreThreshold())
                .toList();

        List<Map<String, Object>> finalCases = rankAndDiversify(question, filteredCases);

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("original_question", question);
        response.put("search_query", searchQuery);
        response.put("qdrant_hits", similarCases.size());
        response.put("after_threshold", filteredCases.size());
        response.put("final", toSources(finalCases));
        return response;
    }

    /**
     * Qdrant 적재 데이터에서 랜덤 샘플 추출 → Groq로 카테고리 체계 제안.
     * 사외(DB 접근 불가) 환경에서도 돌아가도록 MariaDB 아닌 Qdrant payload를 소스로 사용.
     */
    public Map<String, Object> analyzeCategories(int sampleSize) throws Exception {
        List<Map<String, Object>> all = qdrantService.scrollAllPayloads().stream()
                .filter(p -> {
                    Map<String, Object> payload = (Map<String, Object>) p.get("payload");
                    return payload == null || !PointType.FAQ.name().equals(payload.get("type"));
                })
                .collect(Collectors.toList());
        if (all.isEmpty()) {
            Map<String, Object> empty = new LinkedHashMap<>();
            empty.put("total_in_qdrant", 0);
            empty.put("sample_size", 0);
            empty.put("analysis", "{\"error\": \"Qdrant에 적재된 데이터가 없습니다.\"}");
            return empty;
        }

        Collections.shuffle(all);
        int n = Math.min(sampleSize, all.size());
        List<Map<String, Object>> samples = all.subList(0, n);

        List<Map<String, String>> samplePayloads = new ArrayList<>();
        List<Long> sampleIds = new ArrayList<>();
        for (Map<String, Object> point : samples) {
            Map<String, Object> payload = (Map<String, Object>) point.get("payload");
            String title = payload.get("title") != null ? String.valueOf(payload.get("title")) : "";
            String report = payload.get("report") != null ? String.valueOf(payload.get("report")) : "";
            if (title.length() > 100) title = title.substring(0, 100);
            if (report.length() > 500) report = report.substring(0, 500);

            Map<String, String> s = new LinkedHashMap<>();
            s.put("title", title);
            s.put("report", report);
            samplePayloads.add(s);
            if (point.get("id") instanceof Number id) sampleIds.add(id.longValue());
        }

        log.info("카테고리 분석 시작: 전체 {}건 중 {}건 샘플링", all.size(), n);
        String analysis = groqService.proposeCategories(samplePayloads);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("total_in_qdrant", all.size());
        result.put("sample_size", n);
        result.put("sample_ids", sampleIds);
        result.put("analysis", analysis);
        return result;
    }

    /** Reranker 재정렬 + MMR 다양성 선택 — ask()와 searchOnly() 공통 로직. */
    private List<Map<String, Object>> rankAndDiversify(String question,
                                                        List<Map<String, Object>> filteredCases) {
        if (props.getReranker().isEnabled() && !filteredCases.isEmpty()) {
            try {
                int rerankTopK = props.getDedup().isEnabled() ? filteredCases.size() : props.getSearch().getFinalTopK();
                List<Map<String, Object>> reranked = rerankerService.rerank(question, filteredCases, rerankTopK);
                List<Map<String, Object>> afterMin = reranked.stream()
                        .filter(c -> c.get("rerank_score") instanceof Number n && n.doubleValue() >= props.getReranker().getMinScore())
                        .toList();
                log.info("Rerank {}건 → min-score({}) 컷 {}건 → MMR({}) 최종 {}건",
                        reranked.size(), props.getReranker().getMinScore(), afterMin.size(),
                        props.getDedup().isEnabled() ? "ON" : "OFF", props.getSearch().getFinalTopK());
                return applyMMR(afterMin, props.getSearch().getFinalTopK());
            } catch (Exception e) {
                log.warn("Reranker 호출 실패, Qdrant 순위로 폴백: {}", e.getMessage());
                return applyMMR(filteredCases, props.getSearch().getFinalTopK());
            }
        }
        return applyMMR(filteredCases, props.getSearch().getFinalTopK());
    }

    /**
     * MMR (Maximal Marginal Relevance) — 다양성 재정렬.
     * Top 1은 rerank 최고점 그대로(답 놓치지 않기 위한 안전장치), 나머지는
     * "관련성 × λ - 기존 선택과의 최대 유사도 × (1-λ)"로 선택.
     * 텍스트 Jaccard 유사도 기반(벡터 재계산 불필요, 빠름).
     * MMR로 밀려난 유사 사례 수는 payload에 similar_count로 기록 → UI "비슷한 N건 더" 힌트.
     */
    private List<Map<String, Object>> applyMMR(List<Map<String, Object>> candidates, int topK) {
        if (!props.getDedup().isEnabled() || candidates == null || candidates.size() <= topK) {
            return candidates == null ? new ArrayList<>()
                    : candidates.stream().limit(topK).toList();
        }
        List<Map<String, Object>> remaining = new ArrayList<>(candidates);
        List<Map<String, Object>> selected = new ArrayList<>();

        selected.add(remaining.remove(0));

        while (selected.size() < topK && !remaining.isEmpty()) {
            double bestScore = Double.NEGATIVE_INFINITY;
            int bestIdx = -1;
            for (int i = 0; i < remaining.size(); i++) {
                Map<String, Object> cand = remaining.get(i);
                double relevance = getMmrRelevance(cand);
                double maxSim = 0.0;
                String candText = getMmrText(cand);
                for (Map<String, Object> sel : selected) {
                    double sim = jaccardSimilarity(candText, getMmrText(sel));
                    if (sim > maxSim) maxSim = sim;
                }
                double mmrScore = props.getDedup().getLambda() * relevance - (1 - props.getDedup().getLambda()) * maxSim;
                if (mmrScore > bestScore) { bestScore = mmrScore; bestIdx = i; }
            }
            if (bestIdx < 0) break;
            selected.add(remaining.remove(bestIdx));
        }

        // 남은 후보 중 selected와 매우 유사한(임계값 초과) 건수 카운트 → similar_count 메타
        for (Map<String, Object> sel : selected) {
            String selText = getMmrText(sel);
            int cnt = 0;
            for (Map<String, Object> rem : remaining) {
                if (jaccardSimilarity(selText, getMmrText(rem)) >= props.getDedup().getJaccardThreshold()) cnt++;
            }
            sel.put("similar_count", cnt);
        }
        return selected;
    }

    private double getMmrRelevance(Map<String, Object> c) {
        if (c.get("rerank_score") instanceof Number n) return n.doubleValue();
        return c.get("score") instanceof Number n ? n.doubleValue() : 0.0;
    }

    @SuppressWarnings("unchecked")
    private String getMmrText(Map<String, Object> c) {
        Map<String, Object> payload = (Map<String, Object>) c.get("payload");
        if (payload == null) return "";
        // HyDE 컬렉션이면 core_question+situation (짧고 정제됨) 우선, 아니면 report
        Object cq = payload.get("core_question");
        if (cq != null && !String.valueOf(cq).trim().isEmpty()) {
            return String.valueOf(cq) + " " + String.valueOf(payload.getOrDefault("situation", ""));
        }
        Object rep = payload.get("report");
        return rep != null ? String.valueOf(rep) : "";
    }

    private double jaccardSimilarity(String a, String b) {
        Set<String> ta = tokenize(a);
        Set<String> tb = tokenize(b);
        if (ta.isEmpty() || tb.isEmpty()) return 0.0;
        Set<String> inter = new HashSet<>(ta);
        inter.retainAll(tb);
        int unionSize = ta.size() + tb.size() - inter.size();
        return unionSize == 0 ? 0.0 : (double) inter.size() / unionSize;
    }

    private static final Pattern TOKENIZE_SPLIT = Pattern.compile("[^가-힣a-zA-Z0-9]+");

    private Set<String> tokenize(String s) {
        if (s == null || s.isEmpty()) return Collections.emptySet();
        Set<String> tokens = new HashSet<>();
        for (String t : TOKENIZE_SPLIT.split(s.toLowerCase())) {
            if (t.length() >= 2) tokens.add(t);   // 1글자 토큰 제외 (노이즈)
        }
        return tokens;
    }

    /** SearchMode → 컬렉션 이름. TEMPLATED → inquiry_templated, DEFAULT(미지원 값 폴백) → inquiry */
    private String resolveCollection(String mode) {
        return switch (SearchMode.fromString(mode)) {
            case TEMPLATED -> qdrantService.getTemplatedCollection();
            case DEFAULT -> qdrantService.getDefaultCollection();
        };
    }

    private List<Map<String, Object>> toSources(List<Map<String, Object>> cases) {
        List<Map<String, Object>> sources = new ArrayList<>();
        for (Map<String, Object> c : cases) {
            Map<String, Object> payload = (Map<String, Object>) c.get("payload");
            if (payload == null) continue;
            Map<String, Object> s = new LinkedHashMap<>();
            s.put("seq_no", payload.get("seq_no"));
            s.put("prop_cd", payload.get("prop_cd"));
            // title 제거 (2026-04-20) — 실데이터 미사용 확인 후 payload에서 완전히 제거
            s.put("report", payload.get("report"));
            s.put("feedback", payload.get("feedback"));
            // 접수시스템/접수내용 — UI 카드에 태그로 표시, 추후 필터 옵션용
            s.put("system_cd", payload.get("system_cd"));
            s.put("system_nm", payload.get("system_nm"));
            s.put("system_tp_dtl", payload.get("system_tp_dtl"));
            s.put("system_tp_dtl_nm", payload.get("system_tp_dtl_nm"));
            // HyDE 템플릿 필드 (templated 컬렉션에만 존재. default 컬렉션엔 null)
            if (payload.containsKey("core_question")) s.put("core_question", payload.get("core_question"));
            if (payload.containsKey("situation")) s.put("situation", payload.get("situation"));
            if (payload.containsKey("cause")) s.put("cause", payload.get("cause"));
            if (payload.containsKey("solution")) s.put("solution", payload.get("solution"));
            s.put("score", c.get("score"));
            if (c.containsKey("rerank_score")) {
                s.put("rerank_score", c.get("rerank_score"));
            }
            // MMR로 밀려난 유사 사례 수 (UI에 "비슷한 N건 더" 배지 노출용)
            if (c.containsKey("similar_count")) {
                s.put("similar_count", c.get("similar_count"));
            }
            sources.add(s);
        }
        return sources;
    }

    private List<Map<String, Object>> toFaqSources(List<Map<String, Object>> cases) {
        List<Map<String, Object>> sources = new ArrayList<>();
        for (Map<String, Object> c : cases) {
            Map<String, Object> payload = (Map<String, Object>) c.get("payload");
            if (payload == null) continue;
            Map<String, Object> s = new LinkedHashMap<>();
            s.put("faq_id", payload.get("faq_id"));
            s.put("category", payload.get("category"));
            s.put("question", payload.get("question"));
            s.put("answer", payload.get("answer"));
            s.put("score", c.get("score"));
            sources.add(s);
        }
        return sources;
    }
}
