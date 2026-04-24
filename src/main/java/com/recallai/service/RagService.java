package com.recallai.service;

import com.recallai.dto.HotelDto;
import com.recallai.dto.KokCallMntrDto;
import com.recallai.repository.KokCallMntrMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Service
public class RagService {

    private static final Logger log = LoggerFactory.getLogger(RagService.class);

    @Autowired
    private KokCallMntrMapper mapper;

    @Autowired
    private OllamaService ollamaService;

    @Autowired
    private QdrantService qdrantService;

    @Autowired
    private GroqService groqService;

    @Autowired
    private RerankerService rerankerService;

    @Autowired
    private QueryRewriteService queryRewriteService;

    @Autowired
    private IndexFailureTracker failureTracker;

    @Autowired
    private HotelCacheService hotelCacheService;

    /**
     * 등록된 모든 TemplatizeService 구현체.
     * Spring이 bean name → 구현체 Map으로 자동 주입 (예: "groq" → GroqService).
     * resolveTemplatizer()에서 provider 설정값으로 골라 씀.
     */
    @Autowired
    private Map<String, TemplatizeService> templatizers;

    @Value("${rag.templatize.provider:groq}")
    private String templatizeProvider;

    @Value("${rag.search.top-k:10}")
    private int searchTopK;

    @Value("${rag.search.final-top-k:3}")
    private int finalTopK;

    @Value("${rag.search.score-threshold:0.5}")
    private double scoreThreshold;

    @Value("${rag.search.faq-top-k:3}")
    private int faqTopK;

    @Value("${rag.reranker.enabled:false}")
    private boolean rerankerEnabled;

    @Value("${rag.reranker.min-score:0.0}")
    private double rerankMinScore;

    @Value("${rag.dedup.enabled:false}")
    private boolean dedupEnabled;

    @Value("${rag.dedup.jaccard-threshold:0.7}")
    private double dedupJaccardThreshold;

    @Value("${rag.dedup.lambda:0.7}")
    private double dedupLambda;

    @Value("${rag.query-rewrite.enabled:false}")
    private boolean queryRewriteEnabled;

    @Value("${rag.data-dir:.}")
    private String dataDir;

    private static final String SYNC_FILE = "last_sync.txt";
    private static final String SYNC_FILE_DEFAULT = "2026-04-15";
    private static final Pattern DATE_PATTERN = Pattern.compile("\\d{4}-\\d{2}-\\d{2}");

    // indexAll / indexUpdated 동시 실행 방지 (스케줄러 + 수동 POST 경합 차단)
    private final AtomicBoolean indexing = new AtomicBoolean(false);

    // MariaDB → 임베딩 → Qdrant 저장
    // 개별 레코드 실패는 스킵하고 계속 진행 (한 건 에러로 전체 중단 방지)
    public String indexAll() throws Exception {
        if (!indexing.compareAndSet(false, true)) {
            log.warn("indexAll 호출됐지만 이미 다른 적재 작업이 진행 중");
            return "이미 적재 작업이 진행 중입니다";
        }
        try {
            List<KokCallMntrDto> list = mapper.selectAll();
            int ok = 0, skipEmpty = 0, fail = 0;

            for (KokCallMntrDto dto : list) {
                String text = dto.toEmbeddingText();
                if (text.isEmpty()) {
                    skipEmpty++;
                    continue;
                }
                try {
                    List<Double> vector = ollamaService.embed(buildEmbText(dto));
                    qdrantService.upsertTo(qdrantService.getDefaultCollection(), dto.getSeqNo(), vector, dto, buildHotelPayload(dto));
                    ok++;
                    log.info("Indexed [{}/{}] seq_no={} (실패 누계 {})", ok, list.size(), dto.getSeqNo(), fail);
                } catch (Exception e) {
                    fail++;
                    log.warn("Index 실패 seq_no={}, text_len={}, cause={}",
                            dto.getSeqNo(), text.length(), e.getMessage());
                    // 파일 장부에 기록 — 추후 /retry-failed로 재시도 가능
                    failureTracker.record(dto.getSeqNo(), e.getMessage());
                }
            }

            log.info("Index 완료: 성공={}, 실패={}, 빈본문스킵={}", ok, fail, skipEmpty);
            return String.format("저장 완료: 성공 %d건, 실패 %d건, 빈본문 %d건", ok, fail, skipEmpty);
        } finally {
            indexing.set(false);
        }
    }

    /**
     * HyDE 하이브리드 템플릿 적재 — inquiry_templated 컬렉션으로.
     * 각 레코드마다 TemplatizeService(provider 설정 기반) → core_question+situation 임베딩 → HyDE 필드 payload 포함.
     * Groq rate limit 고려해 호출 사이 짧게 sleep.
     */
    // ============================================================
    // MMR (Maximal Marginal Relevance) — 다양성 재정렬
    //   Top 1은 rerank 최고점 그대로, 나머지는 "관련성 × λ - 기존 선택과의 최대 유사도 × (1-λ)"로 선택.
    //   텍스트 Jaccard 유사도 기반 (벡터 재계산 불필요, 빠름).
    //   MMR로 밀려난 유사 사례 수는 payload에 similar_count로 기록 → UI에서 "비슷한 N건 더" 힌트 가능.
    // ============================================================
    private List<Map<String, Object>> applyMMR(List<Map<String, Object>> candidates, int topK) {
        if (!dedupEnabled || candidates == null || candidates.size() <= topK) {
            return candidates == null ? new ArrayList<>()
                    : candidates.stream().limit(topK).collect(Collectors.toList());
        }
        List<Map<String, Object>> remaining = new ArrayList<>(candidates);
        List<Map<String, Object>> selected = new ArrayList<>();

        // 1순위: rerank 최고점 그대로 (답 놓치지 않기 위한 안전장치)
        selected.add(remaining.remove(0));

        // 2순위 이후: MMR 점수로 선택
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
                double mmrScore = dedupLambda * relevance - (1 - dedupLambda) * maxSim;
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
                if (jaccardSimilarity(selText, getMmrText(rem)) >= dedupJaccardThreshold) cnt++;
            }
            sel.put("similar_count", cnt);
        }
        return selected;
    }

    private double getMmrRelevance(Map<String, Object> c) {
        Object rs = c.get("rerank_score");
        if (rs instanceof Number) return ((Number) rs).doubleValue();
        Object s = c.get("score");
        return s instanceof Number ? ((Number) s).doubleValue() : 0.0;
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

    /** 호텔명 접두어 포함한 임베딩 텍스트 생성. 호텔명 없으면 원본 그대로. */
    private String buildEmbText(KokCallMntrDto dto) {
        String hotelNm = hotelCacheService.getShrtNm(dto.getPropCd());
        String base = dto.toEmbeddingText();
        return hotelNm.isEmpty() ? base : "[" + hotelNm + "] " + base;
    }

    /** prop_shrt_nm + cmpx_nm을 포함한 호텔 payload 맵. */
    private Map<String, Object> buildHotelPayload(KokCallMntrDto dto) {
        Map<String, Object> m = new HashMap<>();
        String propShrtNm = hotelCacheService.getShrtNm(dto.getPropCd());
        m.put("prop_shrt_nm", propShrtNm);
        HotelDto hotel = hotelCacheService.getHotel(dto.getPropCd());
        if (hotel != null && dto.getCmpxCd() != null) {
            hotel.getComplexes().stream()
                    .filter(c -> dto.getCmpxCd().equals(c.getCmpxCd()))
                    .findFirst()
                    .ifPresent(c -> m.put("cmpx_nm", c.getCmpxNm()));
        }
        return m;
    }

    /** Reranker 재정렬 + MMR 다양성 선택 — ask()와 searchOnly() 공통 로직. */
    private List<Map<String, Object>> rankAndDiversify(String question,
                                                        List<Map<String, Object>> filteredCases) {
        if (rerankerEnabled && !filteredCases.isEmpty()) {
            try {
                int rerankTopK = dedupEnabled ? filteredCases.size() : finalTopK;
                List<Map<String, Object>> reranked = rerankerService.rerank(question, filteredCases, rerankTopK);
                List<Map<String, Object>> afterMin = reranked.stream()
                        .filter(c -> {
                            Object rs = c.get("rerank_score");
                            return rs instanceof Number && ((Number) rs).doubleValue() >= rerankMinScore;
                        })
                        .collect(Collectors.toList());
                log.info("Rerank {}건 → min-score({}) 컷 {}건 → MMR({}) 최종 {}건",
                        reranked.size(), rerankMinScore, afterMin.size(),
                        dedupEnabled ? "ON" : "OFF", finalTopK);
                return applyMMR(afterMin, finalTopK);
            } catch (Exception e) {
                log.warn("Reranker 호출 실패, Qdrant 순위로 폴백: {}", e.getMessage());
                return applyMMR(filteredCases, finalTopK);
            }
        }
        return applyMMR(filteredCases, finalTopK);
    }

    /** rag.templatize.provider 설정값에 따라 알맞은 TemplatizeService 선택. */
    private TemplatizeService resolveTemplatizer() {
        TemplatizeService svc = templatizers.get(templatizeProvider);
        if (svc == null) {
            throw new IllegalStateException(
                    "Unknown templatize provider='" + templatizeProvider + "'. 사용 가능: " + templatizers.keySet());
        }
        return svc;
    }

    public String indexAllTemplated() throws Exception {
        if (!indexing.compareAndSet(false, true)) {
            log.warn("indexAllTemplated 호출됐지만 이미 다른 적재 작업이 진행 중");
            return "이미 적재 작업이 진행 중입니다";
        }
        try {
            TemplatizeService templatizer = resolveTemplatizer();
            String collectionName = qdrantService.getTemplatedCollection();
            // 이미 적재된 seq_no는 스킵 (중복 LLM 호출 방지 — 토큰 절약)
            Set<Integer> existingSeqs = qdrantService.collectSeqNos(collectionName);
            log.info("Templated 시작: provider={}, collection={}, 기존 {}건 skip 대상",
                    templatizer.providerName(), collectionName, existingSeqs.size());

            List<KokCallMntrDto> list = mapper.selectAll();
            int ok = 0, skipEmpty = 0, skipExisting = 0, failLlm = 0, failUpsert = 0;
            int consecutiveFails = 0;
            final int CONSECUTIVE_FAIL_LIMIT = 10;  // 연속 실패 → 외부 API 한도 추정 → 조기 중단

            for (KokCallMntrDto dto : list) {
                if (existingSeqs.contains(dto.getSeqNo())) {
                    skipExisting++;
                    continue;
                }
                String rawReport = dto.getReport();
                if (rawReport == null || rawReport.trim().isEmpty()) {
                    skipEmpty++;
                    continue;
                }
                // 1) 선택된 provider로 HyDE 추출
                Map<String, Object> hyde = templatizer.templatize(rawReport, dto.getFeedback());
                if (hyde == null) {
                    failLlm++;
                    consecutiveFails++;
                    failureTracker.record(dto.getSeqNo(), "templated: " + templatizer.providerName() + " templatize 실패");
                    if (consecutiveFails >= CONSECUTIVE_FAIL_LIMIT) {
                        log.warn("연속 {}건 LLM 실패 → 외부 API 한도 가능성, 조기 중단. (성공 {} / 전체 예정 {})",
                                consecutiveFails, ok, list.size() - existingSeqs.size());
                        break;
                    }
                    continue;
                }
                consecutiveFails = 0;  // 성공 한 번 나오면 카운터 초기화

                String coreQ = String.valueOf(hyde.getOrDefault("core_question", "")).trim();
                String situation = String.valueOf(hyde.getOrDefault("situation", "")).trim();
                String embText = (coreQ + " " + situation).trim();
                if (embText.isEmpty()) {
                    skipEmpty++;
                    failureTracker.record(dto.getSeqNo(), "templated: 빈 core_question+situation");
                    continue;
                }
                // 2) 임베딩 + 3) Qdrant upsert (HyDE 필드 payload 포함)
                try {
                    List<Double> vector = ollamaService.embed(embText);
                    qdrantService.upsertTo(collectionName, dto.getSeqNo(), vector, dto, hyde);
                    ok++;
                    // 성공 시 실패 장부에서 제거 (재시도 성공 케이스)
                    failureTracker.removeSuccessful(Collections.singleton(dto.getSeqNo()));
                    if (ok % 50 == 0) {
                        log.info("Templated [{}/{}] seq_no={} (LLM실패 {}, Upsert실패 {})",
                                ok, list.size(), dto.getSeqNo(), failLlm, failUpsert);
                    }
                } catch (Exception e) {
                    failUpsert++;
                    log.warn("Templated upsert 실패 seq_no={}, cause={}", dto.getSeqNo(), e.getMessage());
                    failureTracker.record(dto.getSeqNo(), "templated: " + e.getMessage());
                }
                // Groq rate limit 여유 (무료 tier 기준): 호출 사이 50ms 대기
                try { Thread.sleep(50); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
            }

            log.info("Templated Index 완료: 성공={}, LLM실패={}, Upsert실패={}, 빈본문={}, 기존스킵={}",
                    ok, failLlm, failUpsert, skipEmpty, skipExisting);
            return String.format("템플릿 적재 완료: 신규성공 %d건, LLM실패 %d건, Upsert실패 %d건, 빈본문 %d건, 기존스킵 %d건",
                    ok, failLlm, failUpsert, skipEmpty, skipExisting);
        } finally {
            indexing.set(false);
        }
    }

    /**
     * failed_index.txt에 쌓인 실패 seq_no들을 DB에서 재조회해 다시 적재.
     * 성공한 건은 장부에서 제거. 실패하면 같은 줄이 또 append돼서 최신 reason으로 갱신됨.
     */
    public Map<String, Object> retryFailed() throws Exception {
        if (!indexing.compareAndSet(false, true)) {
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("success", false);
            r.put("message", "이미 다른 적재 작업이 진행 중");
            return r;
        }
        try {
            Set<Integer> pendingSeqs = failureTracker.distinctSeqNos();
            if (pendingSeqs.isEmpty()) {
                Map<String, Object> r = new LinkedHashMap<>();
                r.put("success", true);
                r.put("message", "재시도할 실패 건이 없습니다");
                r.put("retried", 0);
                return r;
            }

            // DB에서 해당 seq_no들 원본 재조회 (동일 필터 유지)
            List<KokCallMntrDto> rows = mapper.selectBySeqNos(pendingSeqs);
            Set<Integer> foundSeqs = rows.stream().map(KokCallMntrDto::getSeqNo).collect(Collectors.toSet());
            Set<Integer> missingSeqs = new HashSet<>(pendingSeqs);
            missingSeqs.removeAll(foundSeqs);
            // DB에서 더 이상 조건 안 맞는 건들(FEEDBACK_YN 바뀜 등)은 장부에서 제거 — 영구 제외
            if (!missingSeqs.isEmpty()) {
                log.info("재시도 대상에서 사라진 seq_no {}건 — 장부 정리: {}",
                        missingSeqs.size(), missingSeqs);
                failureTracker.removeSuccessful(missingSeqs);
            }

            int ok = 0, fail = 0, skipEmpty = 0;
            Set<Integer> successSeqs = new HashSet<>();
            for (KokCallMntrDto dto : rows) {
                String text = dto.toEmbeddingText();
                if (text.isEmpty()) {
                    skipEmpty++;
                    continue;
                }
                try {
                    List<Double> vector = ollamaService.embed(buildEmbText(dto));
                    qdrantService.upsertTo(qdrantService.getDefaultCollection(), dto.getSeqNo(), vector, dto, buildHotelPayload(dto));
                    ok++;
                    successSeqs.add(dto.getSeqNo());
                    log.info("Retry OK seq_no={}", dto.getSeqNo());
                } catch (Exception e) {
                    fail++;
                    log.warn("Retry 실패 seq_no={}, cause={}", dto.getSeqNo(), e.getMessage());
                    failureTracker.record(dto.getSeqNo(), "retry: " + e.getMessage());
                }
            }
            failureTracker.removeSuccessful(successSeqs);

            Map<String, Object> r = new LinkedHashMap<>();
            r.put("success", true);
            r.put("pending_before", pendingSeqs.size());
            r.put("retried", rows.size());
            r.put("ok", ok);
            r.put("fail", fail);
            r.put("skip_empty", skipEmpty);
            r.put("removed_missing", missingSeqs.size());
            r.put("pending_after", failureTracker.distinctSeqNos().size());
            log.info("재시도 완료: {}", r);
            return r;
        } finally {
            indexing.set(false);
        }
    }

    public Map<String, Object> failedSummary() {
        List<Map<String, Object>> all = failureTracker.readAll();
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("total", all.size());
        r.put("distinct", failureTracker.distinctSeqNos().size());
        r.put("samples", all.stream().limit(10).collect(Collectors.toList()));
        // 사유별 집계 (상위 10종)
        Map<String, Long> byReason = all.stream()
                .collect(Collectors.groupingBy(
                        e -> String.valueOf(e.getOrDefault("reason", "")),
                        Collectors.counting()));
        r.put("reason_counts", byReason.entrySet().stream()
                .sorted(Map.Entry.<String, Long>comparingByValue().reversed())
                .limit(10)
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue,
                        (a, b) -> a, LinkedHashMap::new)));
        return r;
    }

    // 질문 → (선택) Query Rewrite → 유사 사례 검색 → (선택) Reranker → Groq 답변
    // propCd는 null/빈 문자열이면 필터 없음. mode는 null/"default" 또는 "templated" (A/B 컬렉션 분기).
    public Map<String, Object> ask(String question, String propCd) throws Exception {
        return ask(question, propCd, null);
    }

    public Map<String, Object> ask(String question, String propCd, String mode) throws Exception {
        String collectionName = resolveCollection(mode);

        // 1. (옵션) 질문 확장 — 검색 품질 향상
        String searchQuery = queryRewriteEnabled ? queryRewriteService.rewrite(question) : question;

        // 2. 질문 임베딩
        List<Double> queryVector = ollamaService.embed(searchQuery);

        // 3-FAQ. FAQ 검색 (type=FAQ 필터, propCd 무관, reranker 미적용)
        List<Map<String, Object>> faqRaw = qdrantService.searchIn(collectionName, queryVector, faqTopK, null, "FAQ");
        List<Map<String, Object>> faqResults = faqRaw.stream()
                .filter(c -> c.get("score") instanceof Number && ((Number) c.get("score")).doubleValue() >= scoreThreshold)
                .collect(Collectors.toList());
        log.info("FAQ 검색 결과: {}건 (threshold 후: {}건)", faqRaw.size(), faqResults.size());

        // 3. Qdrant에서 REAL Top N 검색 (reranker가 있으면 넉넉히, 없으면 finalTopK만)
        int fetchK = rerankerEnabled ? searchTopK : finalTopK;
        List<Map<String, Object>> similarCases = qdrantService.searchIn(collectionName, queryVector, fetchK, propCd, "REAL");

        // 4. 유사도 점수 필터
        List<Map<String, Object>> filteredCases = similarCases.stream()
                .filter(c -> c.get("score") instanceof Number && ((Number) c.get("score")).doubleValue() >= scoreThreshold)
                .collect(Collectors.toList());

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

        // 6. Groq LLM으로 답변 생성 — 원본 질문 전달 (확장 쿼리 아님)
        // finalCases가 비어있으면 groqService 내부에서 LLM 호출 없이 기본 메시지 반환
        String answer = groqService.ask(question, finalCases);

        // 7. 응답 구성
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("answer", answer);
        response.put("faq", toFaqSources(faqResults));
        response.put("sources", toSources(finalCases));
        return response;
    }

    /** "templated" → inquiry_templated 컬렉션, 그 외 → default 컬렉션 */
    private String resolveCollection(String mode) {
        if ("templated".equalsIgnoreCase(mode)) {
            return qdrantService.getTemplatedCollection();
        }
        return qdrantService.getDefaultCollection();
    }

    public Map<String, Object> searchOnly(String question, String propCd) throws Exception {
        return searchOnly(question, propCd, null);
    }

    /** 디버그·튜닝용: LLM 거치지 않고 Qdrant + (옵션) Reranker 결과만 반환 */
    public Map<String, Object> searchOnly(String question, String propCd, String mode) throws Exception {
        String collectionName = resolveCollection(mode);
        String searchQuery = queryRewriteEnabled ? queryRewriteService.rewrite(question) : question;
        List<Double> queryVector = ollamaService.embed(searchQuery);
        int fetchK = rerankerEnabled ? searchTopK : finalTopK;
        List<Map<String, Object>> similarCases = qdrantService.searchIn(collectionName, queryVector, fetchK, propCd, "REAL");
        List<Map<String, Object>> filteredCases = similarCases.stream()
                .filter(c -> ((Number) c.get("score")).doubleValue() >= scoreThreshold)
                .collect(Collectors.toList());

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
                    return payload == null || !"FAQ".equals(payload.get("type"));
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
            Object id = point.get("id");
            if (id instanceof Number) sampleIds.add(((Number) id).longValue());
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
            // HyDE 템플릿 필드 (templated 컬렉션의 경우에만 존재. default 컬렉션엔 null)
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

    public String indexUpdated() throws Exception {
        if (!indexing.compareAndSet(false, true)) {
            log.warn("indexUpdated 호출됐지만 이미 다른 적재 작업이 진행 중");
            return "이미 적재 작업이 진행 중입니다";
        }
        try {
            String lastSyncDt = readLastSyncDt();
            List<KokCallMntrDto> list = mapper.selectAfter(lastSyncDt);

            int ok = 0, fail = 0;
            String maxRDt = null;
            for (KokCallMntrDto dto : list) {
                // 조회된 레코드의 실제 최신 R_DT 추적 (커서는 LocalDate.now()가 아닌 데이터 기준)
                if (dto.getRDt() != null && (maxRDt == null || dto.getRDt().compareTo(maxRDt) > 0)) {
                    maxRDt = dto.getRDt();
                }
                String text = dto.toEmbeddingText();
                if (text.isEmpty()) continue;
                try {
                    List<Double> vector = ollamaService.embed(buildEmbText(dto));
                    qdrantService.upsertTo(qdrantService.getDefaultCollection(), dto.getSeqNo(), vector, dto, buildHotelPayload(dto));
                    ok++;
                } catch (Exception e) {
                    fail++;
                    log.warn("증분 적재 실패 seq_no={}, cause={}", dto.getSeqNo(), e.getMessage());
                }
            }

            // 조회된 데이터가 있을 때만 커서 갱신. 0건이면 이전 커서 유지 → 다음 실행에서 재조회 가능.
            if (maxRDt != null) {
                saveLastSyncDt(maxRDt);
                log.info("last_sync 커서 갱신: {} → {} (fetched={}, ok={}, fail={})",
                        lastSyncDt, maxRDt, list.size(), ok, fail);
            } else {
                log.info("신규 데이터 0건, 커서 유지: {}", lastSyncDt);
            }
            return String.format("추가 완료: 성공 %d건, 실패 %d건 (cursor=%s)",
                    ok, fail, maxRDt != null ? maxRDt : lastSyncDt);
        } finally {
            indexing.set(false);
        }
    }

    private String readLastSyncDt() {
        try {
            File file = Paths.get(dataDir, SYNC_FILE).toFile();
            if (!file.exists()) return SYNC_FILE_DEFAULT;
            String content = new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8).trim();
            if (!DATE_PATTERN.matcher(content).matches()) {
                log.warn("{} 내용이 YYYY-MM-DD 형식 아님: '{}' → 기본값 {} 사용",
                        SYNC_FILE, content, SYNC_FILE_DEFAULT);
                return SYNC_FILE_DEFAULT;
            }
            return content;
        } catch (Exception e) {
            log.warn("{} 읽기 실패, 기본값 {} 사용: {}", SYNC_FILE, SYNC_FILE_DEFAULT, e.getMessage());
            return SYNC_FILE_DEFAULT;
        }
    }

    // 원자적 쓰기: tmp 파일에 먼저 쓴 뒤 move. 중간 크래시에도 기존 파일 보존.
    private void saveLastSyncDt(String dt) {
        Path target = Paths.get(dataDir, SYNC_FILE);
        Path tmp = Paths.get(dataDir, SYNC_FILE + ".tmp");
        try {
            Files.write(tmp, dt.getBytes(StandardCharsets.UTF_8));
            try {
                Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (Exception e) {
            log.error("last_sync 저장 실패: {}", dt, e);
            try { Files.deleteIfExists(tmp); } catch (Exception ignored) {}
        }
    }
}
