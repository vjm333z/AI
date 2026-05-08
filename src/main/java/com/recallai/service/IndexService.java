package com.recallai.service;

import com.recallai.config.RagProperties;
import com.recallai.dto.HotelDto;
import com.recallai.dto.KokCallMntrDto;
import com.recallai.repository.KokCallMntrMapper;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.File;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
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

/**
 * Qdrant 적재 파이프라인 — 전체/증분/단건/HyDE 템플릿 적재 + 실패 장부 관리 + last_sync 커서.
 */
@Service
@RequiredArgsConstructor
public class IndexService {

    private static final Logger log = LoggerFactory.getLogger(IndexService.class);

    private final KokCallMntrMapper mapper;
    private final OllamaService ollamaService;
    private final QdrantService qdrantService;
    private final IndexFailureTracker failureTracker;
    private final HotelCacheService hotelCacheService;
    private final RagProperties props;

    /**
     * 등록된 모든 TemplatizeService 구현체.
     * Spring이 bean name → 구현체 Map으로 자동 주입 (예: "groq" → GroqService).
     */
    private final Map<String, TemplatizeService> templatizers;

    /**
     * 무거운 적재 작업(indexAll/indexAllTemplated/indexUpdated/retryFailed) 동시 실행 방지.
     * 스케줄러 + 수동 호출 경합, 운영자 더블클릭 등을 막는 안전망.
     * indexSingle은 단건이라 락 안 잡음.
     */
    private final AtomicBoolean indexing = new AtomicBoolean(false);

    private static final String SYNC_FILE = "last_sync.txt";
    private static final String SYNC_FILE_DEFAULT = "2026-04-15";
    private static final Pattern DATE_PATTERN = Pattern.compile("\\d{4}-\\d{2}-\\d{2}");

    /** MariaDB → 임베딩 → Qdrant 저장. 개별 레코드 실패는 스킵하고 계속 진행. */
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
                    failureTracker.record(dto.getSeqNo(), e.getMessage());
                }
            }

            log.info("Index 완료: 성공={}, 실패={}, 빈본문스킵={}", ok, fail, skipEmpty);
            return String.format("저장 완료: 성공 %d건, 실패 %d건, 빈본문 %d건", ok, fail, skipEmpty);
        } finally {
            indexing.set(false);
        }
    }

    /** HyDE 템플릿 적재 — provider/collection은 application.yml 기본값. limit=0 이면 전체. */
    public String indexAllTemplated(int limit) throws Exception {
        return indexAllTemplatedWith(props.getTemplatize().getProvider(), qdrantService.getTemplatedCollection(), limit);
    }

    /**
     * HyDE 하이브리드 템플릿 적재.
     * 각 레코드마다 TemplatizeService → core_question+situation 임베딩 → HyDE 필드 payload 포함.
     * Groq rate limit 고려해 호출 사이 짧게 sleep.
     */
    public String indexAllTemplatedWith(String provider, String collectionName, int limit) throws Exception {
        if (!indexing.compareAndSet(false, true)) {
            log.warn("indexAllTemplated 호출됐지만 이미 다른 적재 작업이 진행 중");
            return "이미 적재 작업이 진행 중입니다";
        }
        try {
            TemplatizeService templatizer = templatizers.get(provider);
            if (templatizer == null) {
                throw new IllegalStateException("Unknown templatize provider='" + provider + "'. 사용 가능: " + templatizers.keySet());
            }
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
                    // LLM 실패 후 짧은 backoff (OpenAI Tier 1 기준 충분)
                    try { Thread.sleep(1000); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
                    continue;
                }
                consecutiveFails = 0;

                String coreQ = String.valueOf(hyde.getOrDefault("core_question", "")).trim();
                String situation = String.valueOf(hyde.getOrDefault("situation", "")).trim();
                String embText = (coreQ + " " + situation).trim();
                if (embText.isEmpty()) {
                    skipEmpty++;
                    failureTracker.record(dto.getSeqNo(), "templated: 빈 core_question+situation");
                    continue;
                }
                try {
                    List<Double> vector = ollamaService.embed(embText);
                    qdrantService.upsertTo(collectionName, dto.getSeqNo(), vector, dto, hyde);
                    ok++;
                    failureTracker.removeSuccessful(Collections.singleton(dto.getSeqNo()));
                    if (limit > 0 && ok >= limit) {
                        log.info("Templated limit={} 도달, 조기 종료", limit);
                        break;
                    }
                    if (ok % 50 == 0) {
                        log.info("Templated [{}/{}] seq_no={} (LLM실패 {}, Upsert실패 {})",
                                ok, list.size(), dto.getSeqNo(), failLlm, failUpsert);
                    }
                } catch (Exception e) {
                    failUpsert++;
                    log.warn("Templated upsert 실패 seq_no={}, cause={}", dto.getSeqNo(), e.getMessage());
                    failureTracker.record(dto.getSeqNo(), "templated: " + e.getMessage());
                }
                // OpenAI Tier 1: 500 RPM → 200ms 가능. 안전 마진 두고 500ms (6081건 ≈ 50분).
                try { Thread.sleep(500); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
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

            TemplatizeService templatizer = getTemplatizer();
            int ok = 0, fail = 0;
            for (KokCallMntrDto dto : rows) {
                if (indexOneTemplated(dto, templatizer)) {
                    ok++;
                    log.info("Retry OK seq_no={}", dto.getSeqNo());
                } else {
                    fail++;
                }
                try { Thread.sleep(500); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
            }
            // indexOneTemplated 가 성공/실패별로 failureTracker 를 알아서 갱신함

            Map<String, Object> r = new LinkedHashMap<>();
            r.put("success", true);
            r.put("pending_before", pendingSeqs.size());
            r.put("retried", rows.size());
            r.put("ok", ok);
            r.put("fail", fail);
            r.put("removed_missing", missingSeqs.size());
            r.put("pending_after", failureTracker.distinctSeqNos().size());
            log.info("재시도 완료 (templated, provider={}): {}", templatizer.providerName(), r);
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
        r.put("samples", all.stream().limit(10).toList());
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

    /**
     * KOK_CALL_MNTR 등록 직후 단건 즉시 Qdrant 인덱싱 (templated 경로).
     * templatize → core_question+situation 임베딩 → {@code inquiry_templated} 업서트.
     * 실패 시 failed_index.txt에 자동 기록 → retryFailed가 다음 실행에서 자동 복구.
     */
    public void indexSingle(KokCallMntrDto dto) throws Exception {
        TemplatizeService templatizer = getTemplatizer();
        if (!indexOneTemplated(dto, templatizer)) {
            throw new RuntimeException("indexSingle 실패 seq_no=" + dto.getSeqNo()
                    + " — failed_index.txt 에 기록됨, retry-failed 로 복구 가능");
        }
        log.info("indexSingle 완료 seq_no={} (templated, provider={})",
                dto.getSeqNo(), templatizer.providerName());
    }

    /**
     * 증분 적재 — last_sync.txt 이후 신규 KOK_CALL_MNTR을 templated 경로로 업서트.
     * Groq rate limit 보호용 호출 사이 sleep 500ms (indexAllTemplated와 동일).
     */
    public String indexUpdated() throws Exception {
        if (!indexing.compareAndSet(false, true)) {
            log.warn("indexUpdated 호출됐지만 이미 다른 적재 작업이 진행 중");
            return "이미 적재 작업이 진행 중입니다";
        }
        try {
            TemplatizeService templatizer = getTemplatizer();
            String lastSyncDt = readLastSyncDt();
            List<KokCallMntrDto> list = mapper.selectAfter(lastSyncDt);

            int ok = 0, fail = 0;
            String maxRDt = null;
            for (KokCallMntrDto dto : list) {
                // 조회된 레코드의 실제 최신 R_DT 추적 (커서는 LocalDate.now()가 아닌 데이터 기준)
                if (dto.getRDt() != null && (maxRDt == null || dto.getRDt().compareTo(maxRDt) > 0)) {
                    maxRDt = dto.getRDt();
                }
                if (indexOneTemplated(dto, templatizer)) ok++; else fail++;
                try { Thread.sleep(500); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
            }

            // 조회된 데이터가 있을 때만 커서 갱신. 0건이면 이전 커서 유지 → 다음 실행에서 재조회 가능.
            if (maxRDt != null) {
                saveLastSyncDt(maxRDt);
                log.info("last_sync 커서 갱신: {} → {} (fetched={}, ok={}, fail={}, provider={})",
                        lastSyncDt, maxRDt, list.size(), ok, fail, templatizer.providerName());
            } else {
                log.info("신규 데이터 0건, 커서 유지: {}", lastSyncDt);
            }
            return String.format("templated 증분 적재 완료: 성공 %d건, 실패 %d건 (cursor=%s)",
                    ok, fail, maxRDt != null ? maxRDt : lastSyncDt);
        } finally {
            indexing.set(false);
        }
    }

    /**
     * 단일 레코드를 templated 경로로 적재 — single/updated/retry 공통.
     * <ol>
     *   <li>templatize → core_question/situation/cause/solution JSON 추출</li>
     *   <li>core_question + situation 를 임베딩 (HyDE 검색 친화)</li>
     *   <li>{@code inquiry_templated} 컬렉션에 hyde + 호텔 payload 병합 저장</li>
     * </ol>
     * 어느 단계든 실패하면 {@code failed_index.txt} 에 기록하고 false 반환. 성공 시 기존 실패 기록 정리.
     */
    private boolean indexOneTemplated(KokCallMntrDto dto, TemplatizeService templatizer) {
        String rawReport = dto.getReport();
        if (rawReport == null || rawReport.trim().isEmpty()) {
            log.warn("templated 스킵 — 빈 본문 seq_no={}", dto.getSeqNo());
            return false;
        }
        Map<String, Object> hyde = templatizer.templatize(rawReport, dto.getFeedback());
        if (hyde == null) {
            failureTracker.record(dto.getSeqNo(), "templatize 실패: " + templatizer.providerName());
            log.warn("templatize 실패 seq_no={}, provider={}", dto.getSeqNo(), templatizer.providerName());
            return false;
        }
        String coreQ = String.valueOf(hyde.getOrDefault("core_question", "")).trim();
        String situation = String.valueOf(hyde.getOrDefault("situation", "")).trim();
        String embText = (coreQ + " " + situation).trim();
        if (embText.isEmpty()) {
            failureTracker.record(dto.getSeqNo(), "빈 core_question+situation");
            return false;
        }
        try {
            // hyde 4필드 + 호텔 표시명(prop_shrt_nm/cmpx_nm) 모두 payload에 보존
            Map<String, Object> extras = new HashMap<>(hyde);
            extras.putAll(buildHotelPayload(dto));
            List<Double> vector = ollamaService.embed(embText);
            qdrantService.upsertTo(qdrantService.getTemplatedCollection(), dto.getSeqNo(), vector, dto, extras);
            failureTracker.removeSuccessful(Collections.singleton(dto.getSeqNo()));
            return true;
        } catch (Exception e) {
            failureTracker.record(dto.getSeqNo(), "templated upsert: " + e.getMessage());
            log.warn("templated upsert 실패 seq_no={}, cause={}", dto.getSeqNo(), e.getMessage());
            return false;
        }
    }

    /** 현재 설정된 templatize provider 의 빈 조회. 없으면 즉시 예외. */
    private TemplatizeService getTemplatizer() {
        String provider = props.getTemplatize().getProvider();
        TemplatizeService t = templatizers.get(provider);
        if (t == null) {
            throw new IllegalStateException("Unknown templatize provider='" + provider
                    + "'. 사용 가능: " + templatizers.keySet());
        }
        return t;
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

    private String readLastSyncDt() {
        try {
            File file = Paths.get(props.getDataDir(), SYNC_FILE).toFile();
            if (!file.exists()) return SYNC_FILE_DEFAULT;
            var content = Files.readString(file.toPath()).trim();
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
        Path target = Paths.get(props.getDataDir(), SYNC_FILE);
        Path tmp = Paths.get(props.getDataDir(), SYNC_FILE + ".tmp");
        try {
            Files.writeString(tmp, dt);
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
