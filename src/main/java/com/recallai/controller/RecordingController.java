package com.recallai.controller;

import com.recallai.service.RecordingPipelineService;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

/**
 * 녹음 파일 수신·관리 엔드포인트 — kt-call-bot이 직접 호출.
 *
 * <p>기존 python-svc {@code /api/recording*} 와 응답 모양 호환 (kt-call-bot 변경 없이 메인앱으로만 URL 변경).
 * 파일 저장 후 백그라운드 큐에 등록 → 즉시 202 반환.
 */
@RestController
@RequiredArgsConstructor
public class RecordingController {

    private static final Logger log = LoggerFactory.getLogger(RecordingController.class);

    private final RecordingPipelineService pipeline;

    /**
     * 녹음 파일 업로드.
     * <ul>
     *   <li>{@code force=false}(기본): AIA에 동일 BASE_FILE_NM 있거나 inbox에 이미 있으면 409.</li>
     *   <li>{@code force=true}: 무조건 덮어쓰기 + 재처리.</li>
     * </ul>
     */
    @PostMapping(path = "/api/recording", consumes = "multipart/form-data")
    public ResponseEntity<Map<String, Object>> upload(
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "sha256", required = false) String sha256,   // 감사용, 본문엔 사용 안 함
            @RequestParam(value = "force", required = false, defaultValue = "false") boolean force
    ) throws IOException {
        String original = file.getOriginalFilename() != null ? file.getOriginalFilename() : "unknown.mp3";
        String baseName = RecordingPipelineService.normalizeFilename(original);

        if (!force && pipeline.isAlreadyProcessed(baseName)) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of(
                    "exists", true,
                    "filename", baseName,
                    "message", "이미 처리된 파일입니다. 재처리하려면 ?force=true 를 사용하세요."
            ));
        }

        Path inbox = pipeline.getInboxDir().resolve(baseName);
        if (!force && Files.exists(inbox)) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of(
                    "exists", true,
                    "filename", baseName,
                    "message", "처리 대기 중인 파일입니다."
            ));
        }

        pipeline.acceptUpload(baseName, file.getBytes());
        log.info("[API] /api/recording 수신: {}{}", baseName, force ? " [강제 재처리]" : "");

        Map<String, Object> body = new HashMap<>();
        body.put("accepted", true);
        body.put("filename", baseName);
        body.put("force", force);
        body.put("message", "수신 완료. 백그라운드에서 처리 중입니다." + (force ? " (강제 재처리)" : ""));
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(body);
    }

    /** kt-call-bot 조기 중단용 — 이미 처리됐거나 대기 중이면 true. */
    @GetMapping("/api/recording/exists")
    public Map<String, Object> exists(@RequestParam("filename") String filename) {
        String baseName = RecordingPipelineService.normalizeFilename(filename);
        boolean done   = pipeline.isAlreadyProcessed(baseName);
        boolean queued = Files.exists(pipeline.getInboxDir().resolve(baseName));
        return Map.of("exists", done || queued, "filename", baseName);
    }

    /** 파이프라인 현황. */
    @GetMapping("/api/admin/status")
    public Map<String, Object> status() throws IOException {
        return Map.of(
                "inbox_pending", countMp3(pipeline.getInboxDir()),
                "processed",     countMp3(pipeline.getDoneDir()),
                "results",       count(pipeline.getResultsDir(), ".json")
        );
    }

    /** inbox/ 의 미처리 파일 일괄 큐잉 (수동 트리거). */
    @PostMapping("/api/admin/process-inbox")
    public Map<String, Object> processInbox() throws IOException {
        int queued = pipeline.enqueueInbox();
        return Map.of("queued", queued);
    }

    private long countMp3(Path dir) throws IOException { return count(dir, ".mp3"); }

    private long count(Path dir, String suffix) throws IOException {
        if (!Files.isDirectory(dir)) return 0;
        try (var s = Files.list(dir)) {
            return s.filter(p -> p.toString().toLowerCase().endsWith(suffix)).count();
        }
    }
}
