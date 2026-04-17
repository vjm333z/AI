package com.ragtest.service;

import com.ragtest.dto.KokCallMntrDto;
import com.ragtest.repository.KokCallMntrMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
public class RagService {

    @Autowired
    private KokCallMntrMapper mapper;

    @Autowired
    private OllamaService ollamaService;

    @Autowired
    private QdrantService qdrantService;

    @Autowired
    private ClaudeService claudeService;

    private static final String SYNC_FILE = "last_sync.txt";

    // MariaDB → 임베딩 → Qdrant 저장
    public String indexAll() throws Exception {
        List<KokCallMntrDto> list = mapper.selectAll();
        int count = 0;

        for (KokCallMntrDto dto : list) {
            String text = dto.toEmbeddingText();
            if (text.isEmpty()) continue;

            List<Double> vector = ollamaService.embed(text);
            qdrantService.upsert(dto.getSeqNo(), vector, dto);
            count++;

            System.out.println("저장 완료: " + dto.getSeqNo() + " / " + dto.getFeedback());
        }

        return count + "건 저장 완료";
    }

    // 질문 → 유사 사례 검색 → Claude 답변
    public String ask(String question) throws Exception {
        // 1. 질문 임베딩
        List<Double> queryVector = ollamaService.embed(question);

        // 2. 유사 사례 Top 3 검색
        List<Map<String, Object>> similarCases = qdrantService.search(queryVector, 3);

        // 3. 유사도 점수 0.5 미만 필터링 ← 여기 추가
        List<Map<String, Object>> filteredCases = similarCases.stream()
                .filter(c -> (Double) c.get("score") > 0.5)
                .collect(Collectors.toList());

        System.out.println("유사 사례 검색 결과: " + similarCases.size() + "건");
        System.out.println("필터 후 사례: " + filteredCases.size() + "건");
        for (Map<String, Object> c : filteredCases) {
            Map<String, Object> payload = (Map<String, Object>) c.get("payload");
            System.out.println("유사사례 점수: " + c.get("score"));
            System.out.println("유사사례 내용: " + payload.get("report"));
            System.out.println("유사사례 답변: " + payload.get("feedback"));
            System.out.println("---");
        }
        // 4. Claude API로 답변 생성
        return claudeService.ask(question, filteredCases);
    }

    public String indexUpdated() throws Exception {
        // 마지막 동기화 날짜 읽기
        String lastSyncDt = readLastSyncDt();

        // 그 이후 데이터만 조회
        List<KokCallMntrDto> list = mapper.selectAfter(lastSyncDt);

        int count = 0;
        for (KokCallMntrDto dto : list) {
            String text = dto.toEmbeddingText();
            if (text.isEmpty()) continue;
            List<Double> vector = ollamaService.embed(text);
            qdrantService.upsert(dto.getSeqNo(), vector, dto);
            count++;
        }

        // 오늘 날짜 저장
        saveLastSyncDt(LocalDate.now().toString());
        return count + "건 추가 완료";
    }

    private String readLastSyncDt() {
        try {
            File file = new File(SYNC_FILE);
            if (!file.exists()) return "2026-04-15";
            return new String(Files.readAllBytes(file.toPath())).trim();
        } catch (Exception e) {
            return "2026-04-15";
        }
    }

    private void saveLastSyncDt(String dt) {
        try {
            Files.write(Paths.get(SYNC_FILE), dt.getBytes());
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}
