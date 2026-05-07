# 기억사무소 RAG (RecallAI)

사내 AI 경진대회용 호텔 PMS 문의 RAG 답변 시스템. MariaDB의 과거 문의/답변(`KOK_CALL_MNTR`)을 벡터화해 유사 사례 기반 AI 답변을 생성한다.
kt-call-bot 녹음 → STT → AIA_CALL_* DB 저장 파이프라인도 같은 인프라 위에서 운영.

## 아키텍처

```
[적재]  MariaDB → HTML 정제 → Ollama(bge-m3, 1024dim) → Qdrant(inquiry / inquiry_templated)
                                                       ↑ HyDE 템플릿화는 Groq 또는 Claude

[질의]  질문
    → (옵션) 검색용 쿼리 확장               ← QueryRewriteService (Groq)
    → bge-m3 임베딩
    → Qdrant Top N 검색 (옵션 prop_cd 필터, type=REAL/FAQ 분기)
    → score threshold 필터
    → (옵션) bge-reranker-v2-m3 재정렬       ← RerankerService → python-svc:/rerank
    → (옵션) MMR 다양성 재정렬               ← Jaccard 기반, similar_count 메타 부여
    → Claude 답변 생성 (실패 시 Groq 폴백)
    → { answer, faq[], sources[] } 반환

[STT]   kt-call-bot → POST /api/recording (python-svc)
    → Groq Whisper STT → 호텔 매칭(hotels.json + 텍스트 fuzzy + AIA 이력) → 요약 LLM(OpenAI)
    → AIA_CALL_RECORDING / AIA_CALL_TRANSCRIPT / AIA_CALL_ANALYSIS upsert
```

엔드포인트는 `/api/rag/index`(전체), `/api/rag/index/updated`(증분), `/api/rag/ask`(질문). 증분 커서는 `last_sync.txt`.
전체 명세는 [API.md](API.md).

## 기술 스택

- Spring Boot 2.7.18, Java 17, Maven
- MariaDB + MyBatis 2.3.1 (운영 PMS DB)
- Ollama `bge-m3` (1024dim), Qdrant 1.17.1
- Apache HttpClient 4.5.14 + Jackson
- LLM: Anthropic Claude (`claude-sonnet-4-...`) · Groq `llama-3.3-70b-versatile` (폴백)
- python-svc (FastAPI): bge-reranker-v2-m3 + STT 파이프라인 단일 컨테이너

## 주요 파일

### Spring (RAG 앱, port 8080)
- `controller/RagController.java` — `/api/rag/*` 엔드포인트
- `service/RagService.java` — 파이프라인 오케스트레이션 (indexAll / ask / indexUpdated / indexAllTemplated / retryFailed / analyzeCategories)
- `service/OllamaService.java` — bge-m3 임베딩
- `service/QdrantService.java` — upsert / search / scroll, 시동 시 컬렉션 자동 생성 + `prop_cd`·`type` 인덱스
- `service/ClaudeService.java` — Anthropic Messages API (`ask` + `templatize`, bean name `claude`)
- `service/GroqService.java` — Groq Chat Completions (`ask` + `templatize`, bean name `groq`)
- `service/TemplatizeService.java` — HyDE 추출기 인터페이스 (Groq/Claude 공통)
- `service/RerankerService.java` — python-svc `/rerank` HTTP 클라이언트
- `service/QueryRewriteService.java` — 검색용 질의 재작성 (Groq, 실패 시 원문 폴백)
- `service/IndexFaqService.java` — `faq.json` Qdrant 적재 (type=FAQ)
- `service/IndexFailureTracker.java` — 실패 장부(`failed_index.txt`)
- `service/HotelCacheService.java` — `hotels.json` 캐시 + 자동 추가
- `dto/KokCallMntrDto.java` — `toEmbeddingText()` HTML 제거 + 결합
- `repository/KokCallMntrMapper.java` + `resources/mapper/*.xml`
- `resources/application.yml` — Ollama / Qdrant / Claude / Groq + `rag.*` 파이프라인 튜닝
- `resources/static/test.html` — A/B 비교 테스트 UI

### python-svc (port 8000) — `python-svc/`
- `main.py` — FastAPI 진입점, lifespan에서 reranker 모델 백그라운드 로드
- `rerank_router.py` — bge-reranker-v2-m3 CrossEncoder
- `stt_router.py` — `/api/recording` 업로드 + 백그라운드 STT 파이프라인
- `transcribe_summarize.py` — Groq Whisper STT + 요약 LLM (provider: openai | groq)
- `db_utils.py` — AIA_CALL_* upsert
- `hotel_matcher.py` / `corrections.py` — 통화 발/수신번호 → 호텔 매칭

## 빠른 명령어

```bash
# 인프라 전체 (qdrant + ollama + python-svc + app + portainer)
docker-compose up -d
docker exec -it ollama ollama pull bge-m3   # 최초 1회

# Spring Boot 로컬 실행
mvn spring-boot:run

# 상태 한눈에
curl http://localhost:8080/api/rag/status
curl http://localhost:8080/actuator/health
curl http://localhost:8000/health           # python-svc

# 테스트 UI
# http://localhost:8080/test.html

# 전체 재적재
curl -X POST http://localhost:8080/api/rag/index

# Qdrant 리셋 (앱 시동 시 자동 재생성)
curl -X DELETE http://localhost:6333/collections/inquiry
```

> **전체 명령어 레퍼런스**: `C:\Users\eh\OneDrive\문서\Obsidian Vault\사내 AI\RAG\명령어.md`

### 파이프라인 ON/OFF (application.yml)
- `rag.llm.provider` — `claude` | `groq` (Claude 실패 시 Groq 폴백)
- `rag.templatize.provider` — `groq` | `claude` (HyDE 적재용)
- `rag.query-rewrite.enabled` — 검색 전 질의 확장
- `rag.reranker.enabled` — Top N → Top K 재정렬
- `rag.dedup.enabled` — MMR 다양성 재정렬 (jaccard-threshold / lambda 튜닝)
- `rag.search.top-k` / `final-top-k` / `score-threshold` / `faq-score-threshold`
- `rag.scheduler.enabled` / `cron` — 자동 증분 적재

## 개발 시 주의

- **시크릿 분리**: `application.yml`은 환경변수(`${CLAUDE_API_KEY}` 등)로만 주입. 평문 키 커밋 금지.
- **Qdrant 컬렉션 차원**: `inquiry` / `inquiry_templated` 모두 bge-m3 1024차원. 임베딩 모델 교체 시 컬렉션 재생성 필수. `QdrantService.@PostConstruct`가 미존재 컬렉션을 자동 생성하고 `prop_cd`·`type` keyword 인덱스를 부여.
- **Q-Q 매칭 임베딩**: `toEmbeddingText()`는 REPORT만 임베딩. FEEDBACK은 payload에만 저장. TITLE은 `2026-04-20`자로 임베딩·payload 모두에서 제거됨.
- **prop_cd 필터**: payload는 항상 저장, 필터는 요청에 `propCd` 있을 때만. 단일 호텔 환경이면 빈 값.
- **증분 커서**: `data/last_sync.txt`. 파일 없으면 `2026-04-15` 기본. 조회 데이터의 최신 `R_DT`로만 갱신 (0건이면 유지).
- **에러 격리**: `indexAll()`/`indexUpdated()`/`indexAllTemplated()`는 한 레코드 실패해도 다음 진행. 실패는 `failed_index.txt` 장부에 기록 → `/api/rag/index/retry-failed`로 재시도.
- **Query Rewrite는 검색에만**: Reranker·LLM 답변에는 항상 **원본 질문** 전달 (답변 톤 왜곡 방지).
- **STT 파이프라인 격리**: python-svc의 STT 처리에는 `_PROCESS_LOCK`이 걸려 파일 1개씩 순차 처리 (Groq STT 동시 호출 방지). 3초 미만 통화는 자동 스킵.
- **AIA 저장 모델**: STT 결과는 운영 PMS DB의 `AIA_CALL_RECORDING` / `AIA_CALL_TRANSCRIPT` / `AIA_CALL_ANALYSIS`에 직접 upsert (`BASE_FILE_NM` UNIQUE).
- **한국어 답변 고정**: LLM 프롬프트에서 한자·일본어·중국어 사용 금지, "처리 완료"·"해결 완료" 같은 단정 표현 금지 (`ClaudeService` / `GroqService` system prompt).
- **Reranker 콜드 스타트**: python-svc 기동 후 모델 로드까지 30~60초. `RerankerService.@PostConstruct`에서 5초 후 빈 워밍업 호출. `/health.rerank_ready`로 상태 확인.

## 로드맵 참조

**Vault 경로**: `C:\Users\eh\OneDrive\문서\Obsidian Vault\사내 AI\`

- **네비게이션 대시보드**: `기억사무소 RAG 로드맵.md`
- **상시 참고**: `RAG/개요.md`, `RAG/설계.md`, `RAG/명령어.md`, `RAG/STT계획.md`, `RAG/API.md`, `RAG/변경 이력.md`
- **작업 단위 문서**: `RAG/작업/{todo,doing,done}/` (칸반)

작업 운영 규칙: todo/ 시작 → doing/ 착수 → done/ 완료, 각 단계마다 대시보드 + 작업 파일 양쪽 업데이트.
