package com.ragtest.service;

import com.ragtest.dto.KokCallMntrDto;
import com.ragtest.repository.KokCallMntrMapper;
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
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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

    @Value("${rag.search.top-k:10}")
    private int searchTopK;

    @Value("${rag.search.final-top-k:3}")
    private int finalTopK;

    @Value("${rag.search.score-threshold:0.5}")
    private double scoreThreshold;

    @Value("${rag.reranker.enabled:false}")
    private boolean rerankerEnabled;

    @Value("${rag.query-rewrite.enabled:false}")
    private boolean queryRewriteEnabled;

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
                    List<Double> vector = ollamaService.embed(text);
                    qdrantService.upsert(dto.getSeqNo(), vector, dto);
                    ok++;
                    log.info("Indexed [{}/{}] seq_no={} (실패 누계 {})", ok, list.size(), dto.getSeqNo(), fail);
                } catch (Exception e) {
                    fail++;
                    log.warn("Index 실패 seq_no={}, text_len={}, cause={}",
                            dto.getSeqNo(), text.length(), e.getMessage());
                }
            }

            log.info("Index 완료: 성공={}, 실패={}, 빈본문스킵={}", ok, fail, skipEmpty);
            return String.format("저장 완료: 성공 %d건, 실패 %d건, 빈본문 %d건", ok, fail, skipEmpty);
        } finally {
            indexing.set(false);
        }
    }

    // 질문 → (선택) Query Rewrite → 유사 사례 검색 → (선택) Reranker → Groq 답변
    // propCd는 null/빈 문자열이면 필터 없음
    public Map<String, Object> ask(String question, String propCd) throws Exception {
        // 1. (옵션) 질문 확장 — 검색 품질 향상
        String searchQuery = queryRewriteEnabled ? queryRewriteService.rewrite(question) : question;

        // 2. 질문 임베딩
        List<Double> queryVector = ollamaService.embed(searchQuery);

        // 3. Qdrant에서 Top N 검색 (reranker가 있으면 넉넉히, 없으면 finalTopK만)
        int fetchK = rerankerEnabled ? searchTopK : finalTopK;
        List<Map<String, Object>> similarCases = qdrantService.search(queryVector, fetchK, propCd);

        // 4. 유사도 점수 필터
        List<Map<String, Object>> filteredCases = similarCases.stream()
                .filter(c -> ((Number) c.get("score")).doubleValue() >= scoreThreshold)
                .collect(Collectors.toList());

        log.info("유사 사례 검색 결과: {}건", similarCases.size());
        log.info("필터 후 사례: {}건", filteredCases.size());
        for (Map<String, Object> c : filteredCases) {
            Map<String, Object> payload = (Map<String, Object>) c.get("payload");
            log.info("유사사례 점수: {}", c.get("score"));
            log.info("유사사례 내용: {}", payload.get("report"));
            log.info("유사사례 답변: {}", payload.get("feedback"));
            log.info("---");
        }

        // 5. (옵션) Reranker로 재정렬 — 원본 질문 기준 (확장 쿼리 아님)
        List<Map<String, Object>> finalCases;
        if (rerankerEnabled && !filteredCases.isEmpty()) {
            try {
                finalCases = rerankerService.rerank(question, filteredCases, finalTopK);
                log.info("Reranker 적용 후 최종 {}건", finalCases.size());
                for (Map<String, Object> c : finalCases) {
                    log.info("rerank_score: {}, seq_no: {}",
                            c.get("rerank_score"),
                            ((Map<String, Object>) c.get("payload")).get("seq_no"));
                }
            } catch (Exception e) {
                log.warn("Reranker 호출 실패, Qdrant 순위로 폴백: {}", e.getMessage());
                finalCases = filteredCases.stream().limit(finalTopK).collect(Collectors.toList());
            }
        } else {
            finalCases = filteredCases.stream().limit(finalTopK).collect(Collectors.toList());
        }

        // 6. Groq LLM으로 답변 생성 — 원본 질문 전달 (확장 쿼리 아님)
        // finalCases가 비어있으면 groqService 내부에서 LLM 호출 없이 기본 메시지 반환
        String answer = groqService.ask(question, finalCases);

        // 7. 응답 구성 (PMS 팝업 연동용 sources 포함)
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("answer", answer);
        response.put("sources", toSources(finalCases));
        return response;
    }

    /** 디버그·튜닝용: LLM 거치지 않고 Qdrant + (옵션) Reranker 결과만 반환 */
    public Map<String, Object> searchOnly(String question, String propCd) throws Exception {
        String searchQuery = queryRewriteEnabled ? queryRewriteService.rewrite(question) : question;
        List<Double> queryVector = ollamaService.embed(searchQuery);
        int fetchK = rerankerEnabled ? searchTopK : finalTopK;
        List<Map<String, Object>> similarCases = qdrantService.search(queryVector, fetchK, propCd);
        List<Map<String, Object>> filteredCases = similarCases.stream()
                .filter(c -> ((Number) c.get("score")).doubleValue() >= scoreThreshold)
                .collect(Collectors.toList());

        List<Map<String, Object>> finalCases;
        if (rerankerEnabled && !filteredCases.isEmpty()) {
            try {
                finalCases = rerankerService.rerank(question, filteredCases, finalTopK);
            } catch (Exception e) {
                finalCases = filteredCases.stream().limit(finalTopK).collect(Collectors.toList());
            }
        } else {
            finalCases = filteredCases.stream().limit(finalTopK).collect(Collectors.toList());
        }

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("original_question", question);
        response.put("search_query", searchQuery);
        response.put("qdrant_hits", similarCases.size());
        response.put("after_threshold", filteredCases.size());
        response.put("final", toSources(finalCases));
        return response;
    }

    private List<Map<String, Object>> toSources(List<Map<String, Object>> cases) {
        List<Map<String, Object>> sources = new ArrayList<>();
        for (Map<String, Object> c : cases) {
            Map<String, Object> payload = (Map<String, Object>) c.get("payload");
            Map<String, Object> s = new LinkedHashMap<>();
            s.put("seq_no", payload.get("seq_no"));
            s.put("prop_cd", payload.get("prop_cd"));
            s.put("title", payload.get("title"));
            s.put("report", payload.get("report"));
            s.put("feedback", payload.get("feedback"));
            s.put("score", c.get("score"));
            if (c.containsKey("rerank_score")) {
                s.put("rerank_score", c.get("rerank_score"));
            }
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
                    List<Double> vector = ollamaService.embed(text);
                    qdrantService.upsert(dto.getSeqNo(), vector, dto);
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
            File file = new File(SYNC_FILE);
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
        Path target = Paths.get(SYNC_FILE);
        Path tmp = Paths.get(SYNC_FILE + ".tmp");
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
