package com.recallai.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.Marker;
import org.slf4j.MarkerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 인덱싱 실패 건을 파일(failed_index.txt)에 지속적으로 기록하고,
 * 재시도·조회·삭제를 지원한다.
 *
 * <p>형식: {@code seq_no|ISO-timestamp|reason} 한 줄 1건.
 *
 * <p>JVM 재시작에도 살아남도록 파일 기반. 여러 스레드에서 동시 호출되면
 * intrinsic lock으로 직렬화한다 (적재 루프는 단일 스레드지만 안전 차원).
 */
@Service
public class IndexFailureTracker {

    private static final Logger log = LoggerFactory.getLogger(IndexFailureTracker.class);
    private static final Marker INDEX_FAIL = MarkerFactory.getMarker("INDEX_FAIL");
    private static final String FILE = "failed_index.txt";

    @Value("${rag.data-dir:.}")
    private String dataDir;

    private final Object lock = new Object();

    /** 실패 기록 (append). */
    public void record(Integer seqNo, String reason) {
        if (seqNo == null) return;
        String line = seqNo + "|" + Instant.now().toString() + "|" + sanitize(reason) + "\n";
        synchronized (lock) {
            try {
                Files.write(Paths.get(dataDir, FILE), line.getBytes(StandardCharsets.UTF_8),
                        StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            } catch (IOException e) {
                log.warn("실패 장부 기록 실패 seq_no={}: {}", seqNo, e.getMessage());
            }
        }
        // 전용 파일에도 남기기 (logback-spring.xml의 INDEX_FAIL marker 필터로 분리됨)
        log.warn(INDEX_FAIL, "Index 실패 seq_no={}, cause={}", seqNo, sanitize(reason));
    }

    /** 전체 실패 엔트리 읽기 (seq_no, timestamp, reason). */
    public List<Map<String, Object>> readAll() {
        synchronized (lock) {
            Path path = Paths.get(dataDir, FILE);
            if (!Files.exists(path)) return new ArrayList<>();
            try {
                List<String> lines = Files.readAllLines(path, StandardCharsets.UTF_8);
                List<Map<String, Object>> out = new ArrayList<>(lines.size());
                for (String line : lines) {
                    if (line.isEmpty()) continue;
                    String[] parts = line.split("\\|", 3);
                    Map<String, Object> entry = new LinkedHashMap<>();
                    entry.put("seq_no", parseInt(parts.length > 0 ? parts[0] : null));
                    entry.put("timestamp", parts.length > 1 ? parts[1] : "");
                    entry.put("reason", parts.length > 2 ? parts[2] : "");
                    out.add(entry);
                }
                return out;
            } catch (IOException e) {
                log.warn("실패 장부 읽기 실패: {}", e.getMessage());
                return new ArrayList<>();
            }
        }
    }

    /** 고유 seq_no 집합 (재시도 입력). */
    public Set<Integer> distinctSeqNos() {
        Set<Integer> set = new HashSet<>();
        for (Map<String, Object> e : readAll()) {
            Object v = e.get("seq_no");
            if (v instanceof Integer) set.add((Integer) v);
        }
        return set;
    }

    public int count() {
        return readAll().size();
    }

    /** 성공한 seq_no들을 장부에서 제거 (파일 재작성). */
    public void removeSuccessful(Collection<Integer> successSeqs) {
        if (successSeqs == null || successSeqs.isEmpty()) return;
        Set<Integer> done = new HashSet<>(successSeqs);
        synchronized (lock) {
            Path path = Paths.get(dataDir, FILE);
            if (!Files.exists(path)) return;
            try {
                List<String> remain = Files.readAllLines(path, StandardCharsets.UTF_8).stream()
                        .filter(line -> {
                            if (line.isEmpty()) return false;
                            String[] parts = line.split("\\|", 3);
                            Integer sn = parseInt(parts.length > 0 ? parts[0] : null);
                            return sn == null || !done.contains(sn);
                        })
                        .collect(Collectors.toList());
                Files.write(path, remain, StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
            } catch (IOException e) {
                log.warn("실패 장부 정리 실패: {}", e.getMessage());
            }
        }
    }

    /** 장부 전체 비우기. */
    public void clear() {
        synchronized (lock) {
            try {
                Files.deleteIfExists(Paths.get(dataDir, FILE));
            } catch (IOException e) {
                log.warn("실패 장부 삭제 실패: {}", e.getMessage());
            }
        }
    }

    private String sanitize(String s) {
        if (s == null) return "";
        return s.replace("\n", " ").replace("\r", " ").replace("|", "/");
    }

    private Integer parseInt(String s) {
        if (s == null) return null;
        try { return Integer.parseInt(s.trim()); }
        catch (NumberFormatException e) { return null; }
    }
}
