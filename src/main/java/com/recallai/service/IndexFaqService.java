package com.recallai.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.util.*;

@Service
public class IndexFaqService {

    private static final Logger log = LoggerFactory.getLogger(IndexFaqService.class);

    // FAQ Qdrant ID = FAQ_ID_OFFSET + numeric part of faq_id ("faq_001" → 9_000_001)
    private static final long FAQ_ID_OFFSET = 9_000_000L;

    @Autowired
    private OllamaService ollamaService;

    @Autowired
    private QdrantService qdrantService;

    @Value("${qdrant.collection}")
    private String collection;

    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * 기존 REAL 포인트에 type=REAL 일괄 설정 (재임베딩 없음).
     * FAQ 적재 전 1회 실행하거나 /api/rag/index/set-types 로 수동 실행.
     */
    public String setTypesOnExisting() throws Exception {
        qdrantService.setTypeOnExisting(collection);
        return "기존 포인트 type=REAL 설정 완료";
    }

    /**
     * faq.json 읽어서 질문 임베딩 → Qdrant upsert (type=FAQ).
     * 이미 적재된 FAQ도 덮어씌움 (faq_id 기반 고정 ID).
     */
    public String indexFaq() throws Exception {
        List<Map<String, Object>> faqs = loadFaqJson();
        log.info("FAQ 적재 시작: {}건", faqs.size());

        int success = 0, fail = 0;
        for (Map<String, Object> faq : faqs) {
            try {
                String faqId = (String) faq.get("faq_id");
                String question = (String) faq.get("question");
                String answer = (String) faq.get("answer");
                String category = (String) faq.get("category");

                long pointId = FAQ_ID_OFFSET + parseNumericId(faqId);
                List<Double> vector = ollamaService.embed(question);

                Map<String, Object> payload = new LinkedHashMap<>();
                payload.put("type", "FAQ");
                payload.put("faq_id", faqId);
                payload.put("category", category);
                payload.put("question", question);
                payload.put("answer", answer);

                qdrantService.upsertRawPoint(collection, pointId, vector, payload);
                log.debug("FAQ upsert 완료: {} ({})", faqId, question);
                success++;
            } catch (Exception e) {
                log.error("FAQ upsert 실패: {} — {}", faq.get("faq_id"), e.getMessage());
                fail++;
            }
        }

        String msg = String.format("FAQ 적재 완료 — 성공: %d, 실패: %d", success, fail);
        log.info(msg);
        return msg;
    }

    private List<Map<String, Object>> loadFaqJson() throws Exception {
        ClassPathResource resource = new ClassPathResource("faq.json");
        try (InputStream is = resource.getInputStream()) {
            return objectMapper.readValue(is, new TypeReference<List<Map<String, Object>>>() {});
        }
    }

    private int parseNumericId(String faqId) {
        // "faq_001" → 1
        return Integer.parseInt(faqId.replace("faq_", ""));
    }
}
