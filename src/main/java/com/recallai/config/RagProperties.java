package com.recallai.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * RAG 파이프라인 전반의 설정. application.yml의 {@code rag.*} 키들을 그룹화.
 *
 * <p>중첩 클래스로 카테고리별 분리 (search / reranker / dedup / queryRewrite / scheduler / templatize).
 * 각 카테고리는 final 필드로 인스턴스화돼 항상 non-null 보장.
 */
@Data
@ConfigurationProperties(prefix = "rag")
public class RagProperties {

    /** hotels.json / last_sync.txt / failed_index.txt 저장 경로. */
    private String dataDir = ".";

    private final Search search = new Search();
    private final Reranker reranker = new Reranker();
    private final Dedup dedup = new Dedup();
    private final QueryRewrite queryRewrite = new QueryRewrite();
    private final Scheduler scheduler = new Scheduler();
    private final Templatize templatize = new Templatize();

    @Data
    public static class Search {
        /** Qdrant Top-N (reranker 대비 넉넉히). */
        private int topK = 10;
        /** 최종 LLM에 넘길 문서 수. */
        private int finalTopK = 3;
        /** Qdrant cosine 유사도 하한 (bge-m3 기준 0.65+). */
        private double scoreThreshold = 0.5;
        /** FAQ 검색 Top-K. */
        private int faqTopK = 3;
        /** FAQ 점수 하한. */
        private double faqScoreThreshold = 0.60;
    }

    @Data
    public static class Reranker {
        private boolean enabled = false;
        private String url;
        private int timeoutMs = 5000;
        /** rerank_score 컷. 이 아래는 사례에서 제외. */
        private double minScore = 0.0;
    }

    @Data
    public static class Dedup {
        /** MMR 다양성 재정렬 활성화. */
        private boolean enabled = false;
        /** 유사 중복 임계값 (Jaccard, 0~1). */
        private double jaccardThreshold = 0.7;
        /** 관련성 vs 다양성 가중치. 1.0 = 관련성만, 0.0 = 다양성만. */
        private double lambda = 0.7;
    }

    @Data
    public static class QueryRewrite {
        private boolean enabled = false;
        private int timeoutMs = 3000;
    }

    @Data
    public static class Scheduler {
        private boolean enabled = false;
        private String cron = "0 0 3 * * *";
    }

    @Data
    public static class Templatize {
        /** HyDE 적재에 사용할 LLM provider (groq / claude / gemini / openai). */
        private String provider = "groq";
    }
}
