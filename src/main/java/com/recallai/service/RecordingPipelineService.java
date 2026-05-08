package com.recallai.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.recallai.config.RagProperties;
import com.recallai.config.RecordingProperties;
import com.recallai.dto.CallSummaryDto;
import com.recallai.dto.ComplexDto;
import com.recallai.dto.HotelDto;
import com.recallai.dto.RecordingResult;
import com.recallai.dto.SttResultDto;
import com.recallai.repository.AiaCallMapper;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.locks.ReentrantLock;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 녹음 파일 라이프사이클 오케스트레이터.
 *
 * <p>{@code python-svc/stt_router.py}의 _process_recording_inner 자바 이식.
 * 단계: inbox 저장 → STT(python-svc) → 호텔명 보정 → 사전 매칭으로 컨텍스트 → 요약(LLM) → 연락처 주입 →
 * 호텔 매칭(번호→텍스트) → SHA256 → 결과 JSON 저장 → AIA UPSERT → 파일 inbox→done 이동.
 *
 * <p>Groq Whisper 동시 호출 방지: {@link #stt LOCK}로 STT 단계만 직렬화 (요약·DB는 병렬 허용).
 * 백그라운드 처리는 단일 워커 스레드 — STT가 어차피 직렬이라 워커 늘려도 이득 없음.
 */
@Service
@RequiredArgsConstructor
public class RecordingPipelineService {

    private static final Logger log = LoggerFactory.getLogger(RecordingPipelineService.class);

    /** 파일명 패턴: {발신}-{수신}-{YYYYMMDDHHMMSS}-{index}.mp3 — kt-call-bot이 붙이는 (1),(2) 접미사 정규화. */
    private static final Pattern DUP_SUFFIX = Pattern.compile("\\s*\\(\\d+\\)(\\.[^.]+)$");
    private static final Pattern DT_FOURTEEN = Pattern.compile("\\d{14}");
    private static final Pattern DT_PARSE = Pattern.compile("(\\d{4})(\\d{2})(\\d{2})(\\d{2})(\\d{2})(\\d{2})");
    private static final Pattern CONTACT_LINE = Pattern.compile("연락처\\s*:[ \\t]*.*");
    private static final String STT_MODEL = "whisper-large-v3";
    private static final String REG_USER = "STT_AUTO";

    private final RagProperties ragProps;
    private final RecordingProperties recProps;
    private final SttClient sttClient;
    private final HotelMatcherService hotelMatcher;
    private final CallSummarizeService summarizer;
    private final AiaCallMapper aiaMapper;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final ReentrantLock sttLock = new ReentrantLock();
    private ExecutorService executor;

    @PostConstruct
    private void init() throws IOException {
        Files.createDirectories(getInboxDir());
        Files.createDirectories(getDoneDir());
        Files.createDirectories(getResultsDir());
        this.executor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "recording-pipeline");
            t.setDaemon(true);
            return t;
        });
    }

    @PreDestroy
    private void shutdown() {
        if (executor != null) executor.shutdown();
    }

    // ────────────────────────────────────────────────────────────
    // 외부 API (Controller가 호출)
    // ────────────────────────────────────────────────────────────

    /** kt-call-bot이 붙이는 (1),(2) 같은 접미사 제거. */
    public static String normalizeFilename(String filename) {
        if (filename == null) return "unknown.mp3";
        return DUP_SUFFIX.matcher(filename).replaceAll("$1");
    }

    /** AIA에 동일 BASE_FILE_NM이 이미 있으면 true. force=true 재처리 시 회피용. */
    public boolean isAlreadyProcessed(String baseFileName) {
        try {
            return aiaMapper.existsByBaseFileNm(baseFileName) != null;
        } catch (Exception e) {
            // DB 단절 시 results/ 존재 여부로 폴백 판정
            String stem = stripExt(baseFileName);
            return Files.exists(getResultsDir().resolve(stem + ".json"));
        }
    }

    /** 업로드된 바이트를 inbox에 저장 후 백그라운드 처리 큐에 등록. */
    public Path acceptUpload(String baseFileName, byte[] content) throws IOException {
        Path dest = getInboxDir().resolve(baseFileName);
        Files.write(dest, content);
        log.info("[서버] 파일 수신: {} ({} bytes)", baseFileName, content.length);
        executor.submit(() -> processSafe(dest));
        return dest;
    }

    /** inbox/ 의 미처리 파일을 일괄 큐잉 (관리용). */
    public int enqueueInbox() throws IOException {
        int queued = 0;
        try (var stream = Files.list(getInboxDir())) {
            for (Path p : (Iterable<Path>) stream::iterator) {
                if (!p.toString().toLowerCase().endsWith(".mp3")) continue;
                if (isAlreadyProcessed(p.getFileName().toString())) continue;
                executor.submit(() -> processSafe(p));
                queued++;
            }
        }
        return queued;
    }

    public Path getInboxDir()   { return resolveDataPath("inbox"); }
    public Path getDoneDir()    { return resolveDataPath("done"); }
    public Path getResultsDir() { return resolveDataPath("results"); }

    private Path resolveDataPath(String sub) {
        return Paths.get(ragProps.getDataDir(), recProps.getBaseDir(), sub);
    }

    // ────────────────────────────────────────────────────────────
    // 백그라운드 실행 (예외 모두 흡수, 다음 파일 진행)
    // ────────────────────────────────────────────────────────────

    private void processSafe(Path inboxFile) {
        try {
            process(inboxFile);
        } catch (Exception e) {
            log.error("처리 실패 ({}): {}", inboxFile.getFileName(), e.getMessage(), e);
        }
    }

    /** 한 파일에 대한 전체 파이프라인 — 외부에서 동기 처리하고 싶을 때도 호출 가능. */
    public RecordingResult process(Path inboxFile) throws Exception {
        log.info("[서버] 처리 시작: {}", inboxFile.getFileName());
        FileMeta meta = parseFilenameMeta(inboxFile.getFileName().toString());

        // STT 단계만 직렬화 (Groq rate limit 보호)
        SttResultDto stt = sttWithLock(inboxFile);

        // 3초 미만은 잡음·오발신 — STT 후 스킵 (재처리 방지 위해 done으로 이동)
        if (stt.getDurationSec() < recProps.getMinDurationSec()) {
            log.info("[서버] {}초 — {}초 미만이라 스킵 ({})",
                    String.format("%.1f", stt.getDurationSec()), recProps.getMinDurationSec(),
                    inboxFile.getFileName());
            moveToDone(inboxFile);
            return null;
        }

        // 호텔명 alias 보정 (전체 텍스트 + 각 세그먼트)
        if (stt.getText() != null) stt.setText(hotelMatcher.fixHotelNames(stt.getText()));
        if (stt.getSegments() != null) {
            stt.getSegments().forEach(s -> s.setText(hotelMatcher.fixHotelNames(s.getText())));
        }

        // 발/수신번호로 사전 매칭 — LLM 메타 컨텍스트 주입용
        HotelMatcherService.HotelMatch preMatch = hotelMatcher.findByCallNo(meta.callerNo);
        if (!preMatch.isFound()) preMatch = hotelMatcher.findByCallNo(meta.receiverNo);
        String hotelNameForLlm = preMatch.complex() != null ? preMatch.complex().getCmpxNm()
                : (preMatch.hotel() != null ? preMatch.hotel().getPropShrtNm() : null);

        var ctx = new CallSummarizeService.SummarizeContext(
                meta.callerNo, meta.receiverNo, meta.callDt,
                hotelNameForLlm,
                preMatch.hotel() != null ? preMatch.hotel().getPropCd() : null,
                recProps.getDaolReceiverNoSet());
        CallSummaryDto summary = summarizer.summarize(stt.getSegments(), ctx);

        // 연락처 자동 주입 (다올 비전 번호 제외하고 상대측 번호 우선)
        if (summary != null && summary.getReport() != null) {
            String contact = pickCounterpartContact(meta.callerNo, meta.receiverNo);
            if (contact != null) summary.setReport(injectContact(summary.getReport(), contact));
        }

        // 호텔 매칭 풀 캐스케이드 (번호 → 텍스트 fuzzy)
        HotelMatcherService.HotelMatch finalMatch = matchHotelCascade(meta, stt, summary);
        String propCd = finalMatch.hotel() != null ? finalMatch.hotel().getPropCd() : null;
        String cmpxCd = finalMatch.complex() != null ? finalMatch.complex().getCmpxCd() : null;
        // 단일 complex라면 자동으로 채움
        if (finalMatch.hotel() != null && cmpxCd == null) {
            var complexes = finalMatch.hotel().getComplexes();
            if (complexes != null && complexes.size() == 1) {
                cmpxCd = complexes.get(0).getCmpxCd();
            }
        }
        String cmpxNm = pickCmpxName(finalMatch);

        // 결과 모델
        byte[] fileBytes = Files.readAllBytes(inboxFile);
        RecordingResult result = new RecordingResult();
        result.setAudioFile(inboxFile.getFileName().toString());
        result.setBaseFileNm(inboxFile.getFileName().toString());
        result.setFilePath(inboxFile.toString());
        result.setFileSize((long) fileBytes.length);
        result.setSha256(sha256Hex(fileBytes));
        result.setCallerNo(meta.callerNo);
        result.setReceiverNo(meta.receiverNo);
        result.setCallDt(meta.callDt);
        result.setChannelSeq(meta.channelSeq);
        result.setPropCd(propCd);
        result.setCmpxCd(cmpxCd);
        result.setCmpxNm(cmpxNm);
        result.setStt(stt);
        result.setSummary(summary);

        // JSON 사본
        Path jsonPath = getResultsDir().resolve(stripExt(inboxFile.getFileName().toString()) + ".json");
        objectMapper.writerWithDefaultPrettyPrinter().writeValue(jsonPath.toFile(), result);

        // AIA UPSERT
        Integer recSeqNo = saveToAia(result);
        if (recSeqNo != null) {
            result.setRecSeqNo(recSeqNo);
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(jsonPath.toFile(), result);
        }

        // 처리 완료 → done/
        moveToDone(inboxFile);
        log.info("[서버] 처리 완료: {} → done/", inboxFile.getFileName());
        return result;
    }

    private SttResultDto sttWithLock(Path file) throws Exception {
        if (!recProps.isProcessLockEnabled()) {
            return sttClient.transcribe(toSttRelativePath(file));
        }
        sttLock.lock();
        try {
            return sttClient.transcribe(toSttRelativePath(file));
        } finally {
            sttLock.unlock();
        }
    }

    /** Spring 절대경로 → python-svc data-dir 기준 상대경로. (양 컨테이너가 같은 볼륨을 마운트한다고 가정) */
    private String toSttRelativePath(Path file) {
        Path dataRoot = Paths.get(ragProps.getDataDir()).toAbsolutePath();
        Path abs = file.toAbsolutePath();
        if (abs.startsWith(dataRoot)) {
            return dataRoot.relativize(abs).toString().replace('\\', '/');
        }
        // 폴백: 파일명만 — python-svc DATA_DIR 내 어디든 같은 이름이면 동작
        return recProps.getBaseDir() + "/inbox/" + file.getFileName();
    }

    // ────────────────────────────────────────────────────────────
    // 호텔 매칭 캐스케이드
    // ────────────────────────────────────────────────────────────

    private HotelMatcherService.HotelMatch matchHotelCascade(FileMeta meta, SttResultDto stt, CallSummaryDto summary) {
        var match = hotelMatcher.findByCallNo(meta.callerNo);
        if (match.isFound()) return match;
        match = hotelMatcher.findByCallNo(meta.receiverNo);
        if (match.isFound()) return match;
        if (summary == null) return HotelMatcherService.HotelMatch.none();

        // 텍스트 fuzzy — speaker_B 추정 + report + STT 앞 500자
        StringBuilder searchText = new StringBuilder();
        Object speakerB = summary.getRaw().get("speaker_B");
        if (speakerB != null) searchText.append(speakerB).append(" ");
        if (summary.getReport() != null) searchText.append(summary.getReport()).append(" ");
        if (stt.getText() != null) {
            searchText.append(stt.getText().substring(0, Math.min(500, stt.getText().length())));
        }
        var textMatch = hotelMatcher.findFromText(searchText.toString());
        if (textMatch.isFound()) {
            // 같은 호텔의 complex도 텍스트로 좁혀보기
            ComplexDto cmpx = hotelMatcher.findCmpxFromText(searchText.toString(), textMatch.hotel());
            HotelDto h = textMatch.hotel();
            // fuzzy 매칭이면 alias 자동 학습 (다음 통화에 즉시 활용)
            if (textMatch.aliasCandidate() != null) {
                hotelMatcher.learnAlias(h.getPropCd(), textMatch.aliasCandidate());
            }
            return new HotelMatcherService.HotelMatch(h, cmpx, textMatch.aliasCandidate());
        }
        return HotelMatcherService.HotelMatch.none();
    }

    private String pickCmpxName(HotelMatcherService.HotelMatch m) {
        if (m.complex() != null && m.complex().getCmpxNm() != null) return m.complex().getCmpxNm();
        if (m.hotel() != null) return m.hotel().getPropShrtNm();
        return null;
    }

    // ────────────────────────────────────────────────────────────
    // AIA 저장
    // ────────────────────────────────────────────────────────────

    private Integer saveToAia(RecordingResult r) {
        boolean summaryOk = r.getSummary() != null;
        Map<String, Object> recRow = new HashMap<>();
        recRow.put("file_nm",      r.getAudioFile());
        recRow.put("base_file_nm", r.getBaseFileNm());
        recRow.put("sha256",       r.getSha256());
        recRow.put("file_size",    r.getFileSize());
        recRow.put("file_path",    r.getFilePath());
        recRow.put("send_no",      r.getCallerNo());
        recRow.put("recv_no",      r.getReceiverNo());
        recRow.put("call_dt",      r.getCallDt());
        recRow.put("channel_seq",  r.getChannelSeq());
        recRow.put("duration",     r.getStt() != null ? (int) r.getStt().getDurationSec() : null);
        recRow.put("r_status",     summaryOk ? "LMM" : "STT");
        recRow.put("reg_user",     REG_USER);

        try {
            aiaMapper.upsertRecording(recRow);
            Integer recSeqNo = aiaMapper.selectRecSeqNo(r.getBaseFileNm());
            if (recSeqNo == null) {
                log.error("[DB] REC_SEQ_NO 조회 실패 — RECORDING UPSERT 직후");
                return null;
            }
            // TRANSCRIPT
            Map<String, Object> trRow = new HashMap<>();
            trRow.put("rec_seq_no",    recSeqNo);
            trRow.put("stt_text",      r.getStt() != null ? r.getStt().getText() : "");
            trRow.put("segments_json", r.getStt() != null
                    ? objectMapper.writeValueAsString(r.getStt().getSegments()) : "[]");
            trRow.put("stt_model",     STT_MODEL);
            trRow.put("reg_user",      REG_USER);
            aiaMapper.upsertTranscript(trRow);

            // ANALYSIS — 요약 성공 시에만
            if (summaryOk) {
                String[] parsed = parseReportFields(r.getSummary().getReport());
                Map<String, Object> anRow = new HashMap<>();
                anRow.put("rec_seq_no",    recSeqNo);
                anRow.put("cmpx_nm",       r.getCmpxNm());
                anRow.put("cust_nm",       r.getSummary().getCallerNm() != null
                        ? r.getSummary().getCallerNm() : parsed[0]);
                anRow.put("cust_phone",    parsed[1]);
                anRow.put("inq_desc",      r.getSummary().getReport());
                anRow.put("ai_summary",    r.getSummary().getSummary());
                anRow.put("category_cd",   r.getSummary().getCategory());
                anRow.put("urgency_cd",    r.getSummary().getUrgencyCd());
                anRow.put("system_cd",     r.getSummary().getSystemCd());
                anRow.put("system_tp",     r.getSummary().getSystemTp());
                anRow.put("followup_note", r.getSummary().getFeedback());
                anRow.put("llm_model",     summarizer.resolveLlmModel());
                anRow.put("reg_user",      REG_USER);
                aiaMapper.upsertAnalysis(anRow);
            }
            log.info("[DB] AIA UPSERT 완료 (REC_SEQ_NO={}, {})",
                    recSeqNo, summaryOk ? "+ANALYSIS" : "STT only");
            return recSeqNo;
        } catch (Exception e) {
            log.error("[DB] AIA UPSERT 실패: {}", e.getMessage(), e);
            return null;
        }
    }

    // ────────────────────────────────────────────────────────────
    // 보조 — 파일명 메타 파싱, 연락처 주입, report 파싱, 해시
    // ────────────────────────────────────────────────────────────

    /** {발신}-{수신}-{YYYYMMDDHHMMSS}-{index} 형식에서 메타 추출. */
    static FileMeta parseFilenameMeta(String filename) {
        String stem = stripExt(filename);
        String[] parts = stem.split("-");
        if (parts.length < 3) return new FileMeta(null, null, null, null);

        int dtIdx = -1;
        for (int i = 0; i < parts.length; i++) {
            if (DT_FOURTEEN.matcher(parts[i]).matches()) { dtIdx = i; break; }
        }
        if (dtIdx < 2) return new FileMeta(null, null, null, null);

        String callerNo = parts[0];
        StringBuilder receiver = new StringBuilder(parts[1]);
        for (int i = 2; i < dtIdx; i++) receiver.append("-").append(parts[i]);

        Matcher m = DT_PARSE.matcher(parts[dtIdx]);
        String callDt = m.matches()
                ? String.format("%s-%s-%s %s:%s:%s", m.group(1), m.group(2), m.group(3),
                        m.group(4), m.group(5), m.group(6))
                : null;

        Integer channelSeq = null;
        if (dtIdx + 1 < parts.length) {
            try { channelSeq = Integer.parseInt(parts[dtIdx + 1]); } catch (NumberFormatException ignored) {}
        }
        return new FileMeta(callerNo, receiver.toString(), callDt, channelSeq);
    }

    /** report의 "연락처: ..." 줄을 실제 발/수신번호로 치환. */
    static String injectContact(String report, String contact) {
        if (report == null || contact == null) return report;
        return CONTACT_LINE.matcher(report).replaceAll(Matcher.quoteReplacement("연락처: " + contact));
    }

    /** 다올 번호가 아닌 상대측 번호를 우선 선택. */
    private String pickCounterpartContact(String callerNo, String receiverNo) {
        var daol = recProps.getDaolReceiverNoSet();
        if (callerNo != null && !daol.contains(callerNo)) return callerNo;
        if (receiverNo != null && !daol.contains(receiverNo)) return receiverNo;
        return callerNo != null ? callerNo : receiverNo;
    }

    /** report 텍스트에서 "문의자 :", "연락처:" 두 줄을 파싱해 [callerNm, contactNo]. */
    static String[] parseReportFields(String report) {
        String callerNm = null, contactNo = null;
        if (report == null) return new String[]{null, null};
        for (String line : report.split("\\n")) {
            Matcher m1 = Pattern.compile("문의자\\s*:\\s*(.+)").matcher(line);
            if (m1.find()) {
                String v = m1.group(1).trim();
                callerNm = ("미확인".equals(v) || v.isEmpty()) ? null : v;
            }
            Matcher m2 = Pattern.compile("연락처\\s*:\\s*(.+)").matcher(line);
            if (m2.find()) {
                String v = m2.group(1).trim();
                contactNo = ("미확인".equals(v) || v.isEmpty()) ? null : v;
            }
        }
        return new String[]{callerNm, contactNo};
    }

    private void moveToDone(Path file) throws IOException {
        Path target = getDoneDir().resolve(file.getFileName());
        Files.move(file, target, StandardCopyOption.REPLACE_EXISTING);
    }

    private static String stripExt(String filename) {
        int dot = filename.lastIndexOf('.');
        return dot < 0 ? filename : filename.substring(0, dot);
    }

    private static String sha256Hex(byte[] data) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(data);
            StringBuilder sb = new StringBuilder(digest.length * 2);
            for (byte b : digest) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            return null;
        }
    }

    /** 파일명에서 추출한 메타. */
    record FileMeta(String callerNo, String receiverNo, String callDt, Integer channelSeq) {}
}
