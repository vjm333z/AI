package com.recallai.controller;

import com.recallai.config.RagProperties;
import com.recallai.config.RecordingProperties;
import com.recallai.service.RecordingTriggerClient;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 녹음 파일 수신 — kt-call-bot 호출. 공유 볼륨에 저장 후 python-svc 트리거.
 * python-svc 다운 시 inbox에 보존되어 {@code POST :8000/api/admin/process-inbox} 로 회수 가능.
 */
@RestController
@RequiredArgsConstructor
public class RecordingController {

    private static final Logger log = LoggerFactory.getLogger(RecordingController.class);

    /** kt-call-bot이 같은 통화에 (1),(2) 접미사를 붙이는 경우가 있어 정규화. */
    private static final Pattern DUP_SUFFIX = Pattern.compile("\\s*\\(\\d+\\)(\\.[^.]+)$");

    private final RagProperties ragProps;
    private final RecordingProperties recProps;
    private final RecordingTriggerClient triggerClient;

    @PostMapping(path = "/api/recording", consumes = "multipart/form-data")
    public ResponseEntity<Map<String, Object>> upload(
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "sha256", required = false) String sha256   // kt-bot 감사용. 본문 처리엔 미사용.
    ) throws IOException {
        String original = file.getOriginalFilename() != null ? file.getOriginalFilename() : "unknown.mp3";
        String baseName = DUP_SUFFIX.matcher(original).replaceAll("$1");

        // 공유 볼륨에 저장 — python-svc가 같은 경로에서 읽어 처리
        Path inboxDir = Paths.get(ragProps.getDataDir(), recProps.getBaseDir(), "inbox");
        Files.createDirectories(inboxDir);
        Path dest = inboxDir.resolve(baseName);
        Files.write(dest, file.getBytes());
        log.info("[API] /api/recording 수신: {} ({} bytes)", baseName, file.getSize());

        String relativePath = recProps.getBaseDir() + "/inbox/" + baseName;
        boolean triggered = false;
        try {
            triggerClient.triggerProcess(relativePath);
            triggered = true;
        } catch (Exception e) {
            // 트리거 실패해도 파일은 inbox에 보존 — /api/admin/process-inbox 로 복구 가능
            log.warn("python-svc 트리거 실패 (inbox 보존됨): {}", e.getMessage());
        }

        return ResponseEntity.status(HttpStatus.ACCEPTED).body(Map.of(
                "accepted", true,
                "filename", baseName,
                "triggered", triggered
        ));
    }
}
