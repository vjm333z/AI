# API 명세서

베이스 URL: `http://localhost:8080` (RAG 앱), `http://localhost:8000` (python-svc — reranker + STT 통합)

> python-svc는 reranker + STT를 단일 컨테이너로 통합한 서비스입니다.
> STT 결과는 운영 PMS DB의 `AIA_CALL_*` 테이블에 직접 저장됩니다.

---

## RAG 앱 `/api/rag`

### 질문/답변

#### `POST /api/rag/ask`
RAG 파이프라인으로 질문에 답변 생성.

**Request**
```json
{
  "question": "체크인 시간 변경 가능한가요?",
  "propCd": "H001",
  "mode": "default"
}
```
| 필드 | 타입 | 필수 | 설명 |
|------|------|------|------|
| question | string | ✅ | 질문 (최대 2000자) |
| propCd | string | - | 호텔 property 코드. 없으면 전체 검색 |
| mode | string | - | `default` \| `templated` (A/B 비교용 컬렉션 선택) |

**Response**
```json
{
  "success": true,
  "mode": "default",
  "answer": "체크인 시간은 ...",
  "faq": [
    {
      "faq_id": "F001",
      "category": "체크인",
      "question": "FAQ 질문",
      "answer": "FAQ 답변",
      "score": 0.86
    }
  ],
  "sources": [
    {
      "seq_no": 123,
      "prop_cd": "H001",
      "report": "문의 원문",
      "feedback": "답변 원문",
      "system_cd": "...",
      "system_nm": "...",
      "system_tp_dtl": "...",
      "system_tp_dtl_nm": "...",
      "score": 0.87,
      "rerank_score": 0.91,
      "similar_count": 2
    }
  ],
  "elapsed_ms": 420
}
```

> `core_question`/`situation`/`cause`/`solution`은 `mode=templated` 일 때 sources 항목에 추가됩니다.
> `rerank_score`는 reranker 활성화 시, `similar_count`는 MMR 다양성 재정렬(dedup) 활성화 시 포함됩니다.

#### `POST /api/rag/search`
LLM 없이 Qdrant + (옵션) reranker 결과만 반환 (디버그·튜닝용).

**Request** — `/ask`와 동일
**Response**
```json
{
  "success": true,
  "original_question": "...",
  "search_query": "...",
  "qdrant_hits": 10,
  "after_threshold": 5,
  "final": [ /* sources 배열, /ask 와 동일 스키마 */ ]
}
```

---

### 인덱싱

#### `POST /api/rag/index`
MariaDB 전체 데이터를 Qdrant에 적재. 최초 1회 또는 컬렉션 리셋 후 실행.

**Response**
```json
{ "success": true, "message": "저장 완료: 성공 12500건, 실패 3건, 빈본문 12건" }
```

#### `POST /api/rag/index/updated`
`last_sync.txt` 기준 이후 변경분만 증분 적재. 조회된 데이터의 최신 `R_DT`로 커서를 갱신합니다.

**Response**
```json
{ "success": true, "message": "추가 완료: 성공 5건, 실패 0건 (cursor=2026-04-30)" }
```

#### `POST /api/rag/index/single/{seqNo}`
단건 즉시 인덱싱. KOK_CALL_MNTR 저장 직후 호출용.

| 파라미터 | 위치 | 설명 |
|---------|------|------|
| seqNo | path | KOK_CALL_MNTR.SEQ_NO |

**Response**
```json
{ "success": true, "message": "단건 인덱싱 완료 seq_no=12345" }
```

#### `POST /api/rag/index/templated?limit=0`
HyDE 방식 템플릿 적재 — `inquiry_templated` 컬렉션. templatize provider는 `rag.templatize.provider` 설정값 (groq | openai).

| 파라미터 | 위치 | 기본값 | 설명 |
|---------|------|--------|------|
| limit | query | 0 | 0 = 전체, 1 이상이면 해당 건수에서 조기 종료 |

**Response**
```json
{
  "success": true,
  "message": "템플릿 적재 완료: 신규성공 N건, LLM실패 N건, Upsert실패 N건, 빈본문 N건, 기존스킵 N건"
}
```

#### `POST /api/rag/index/faq`
`faq.json` 내용을 Qdrant에 적재 (type=FAQ).

**Response**
```json
{ "success": true, "message": "..." }
```

#### `POST /api/rag/index/set-types`
기존 REAL 포인트에 `type=REAL` payload 일괄 설정 (재임베딩 없음).

**Response** — `/index/faq`와 동일

#### `POST /api/rag/index/retry-failed`
`failed_index.txt`에 기록된 실패 건 재시도. 성공 시 장부에서 제거. DB에서 더 이상 조건 안 맞는 seq_no는 영구 정리.

**Response**
```json
{
  "success": true,
  "pending_before": 12,
  "retried": 12,
  "ok": 10,
  "fail": 2,
  "skip_empty": 0,
  "removed_missing": 0,
  "pending_after": 2
}
```

#### `GET /api/rag/index/failed`
실패 장부 조회. 사유별 집계(상위 10종)·샘플 10건 반환.

**Response**
```json
{
  "success": true,
  "total": 25,
  "distinct": 18,
  "samples": [ { "seq_no": 1, "reason": "...", "ts": "..." } ],
  "reason_counts": { "embed timeout": 12, "...": 5 }
}
```

---

### 분석·관리

#### `POST /api/rag/analyze-categories`
Qdrant 적재 데이터 샘플링 → Groq로 카테고리 체계 제안. `type=FAQ`는 제외.

**Request**
```json
{ "sampleSize": 150 }
```
| 필드 | 타입 | 설명 |
|------|------|------|
| sampleSize | int | 샘플 수 (10~300, 기본 150). 범위 밖이면 클램프 |

**Response**
```json
{
  "success": true,
  "total_in_qdrant": 12500,
  "sample_size": 150,
  "sample_ids": [123, 456, ...],
  "analysis": "{...JSON 문자열...}",
  "elapsed_ms": 1820
}
```

#### `POST /api/rag/hotels/add`
신규 호텔 추가 — PMS 통화업무등록 시 hotels.json에 없는 호텔 자동 등록.

**Request**
```json
{
  "propCd": "H999",
  "propShrtNm": "호텔명 약칭",
  "propFullNm": "호텔명 전체 (선택)",
  "cmpxCd": "C001",
  "cmpxNm": "단지명 (선택)",
  "cmpxReprTel": "02-1234-5678 (선택)"
}
```

**Response**
```json
{ "success": true, "added": true }
```

> `added: false` 는 이미 존재해서 무시했음을 의미합니다.

#### `POST /api/rag/phone-lookup/add`
전화번호 → propCd 매핑 추가.

**Request**
```json
{ "phoneNo": "02-1234-5678", "propCd": "H001" }
```

**Response** — `/hotels/add`와 동일 형태 (`added` boolean)

#### `POST /api/rag/hotels/refresh`
호텔 정보 캐시 새로고침 (DB 재조회 + `hotels.json` 갱신).

**Response**
```json
{ "success": true, "count": 186 }
```

#### `GET /api/rag/status`
시스템 상태 조회.

**Response**
```json
{
  "reranker_enabled": true,
  "query_rewrite_enabled": true,
  "qdrant": { "available": true, "points_count": 12500, "status": "green" },
  "reranker": { "available": true, "body": "{\"status\":\"ok\",\"rerank_ready\":true,\"rerank_error\":null}" }
}
```

---

## python-svc (`:8000`)

reranker + STT 통합 서비스. CORS는 모든 출처 허용.

### Reranker

#### `POST /rerank`
bge-reranker-v2-m3로 query↔documents 관련도 재정렬.

**Request**
```json
{
  "query": "체크인 시간 변경",
  "documents": ["문서1 내용", "문서2 내용", "..."],
  "top_k": 3
}
```
| 필드 | 타입 | 필수 | 기본값 | 설명 |
|------|------|------|--------|------|
| query | string | ✅ | - | 질의 |
| documents | string[] | ✅ | - | 후보 문서 배열 |
| top_k | int | - | 3 | 반환 상위 N |

**Response**
```json
{
  "results": [
    { "index": 2, "score": 0.91 },
    { "index": 0, "score": 0.85 }
  ]
}
```

| 상태 | 의미 |
|------|------|
| 200 | 정상 (모델 로드 완료) |
| 503 | 모델 로딩 중 — 잠시 후 재시도 |
| 500 | 모델 로드 실패 (`detail`에 사유) |

`documents`가 빈 배열이면 200 + `{"results": []}`.

---

### STT

#### `POST /api/recording`
kt-call-bot 녹음 파일 업로드 → 백그라운드 STT 파이프라인 (STT → 호텔 매칭 → 요약 → AIA_CALL_* 저장).

**Request** — `multipart/form-data`

| 필드 | 위치 | 타입 | 필수 | 설명 |
|------|------|------|------|------|
| file | form | binary | ✅ | mp3 등 오디오 파일 |
| sha256 | form | string | - | 감사용 해시 (선택) |
| force | query | bool | - | true 시 이미 처리된 파일도 재처리 |

파일명에 붙는 `(1)`, `(2)` 같은 중복 접미사는 자동 정규화됩니다.

**Response**
```json
// 202 Accepted — 정상 수신
{
  "accepted": true,
  "filename": "recording_20260427.mp3",
  "force": false,
  "message": "수신 완료. 백그라운드에서 처리 중입니다."
}
```
```json
// 409 Conflict — 이미 처리된 파일이거나 inbox 대기 중
{
  "exists": true,
  "filename": "recording_20260427.mp3",
  "message": "이미 처리된 파일입니다. 재처리하려면 ?force=true 를 사용하세요."
}
```

#### `GET /api/recording/exists`
파일 중복 여부 확인. inbox 대기 + AIA_CALL_RECORDING 둘 다 검사.

| 파라미터 | 위치 | 필수 | 설명 |
|---------|------|------|------|
| filename | query | ✅ | 확인할 파일명 |

**Response**
```json
{ "exists": true, "filename": "recording_20260427.mp3" }
```

#### `GET /api/admin/status`
파이프라인 현황.

**Response**
```json
{ "inbox_pending": 2, "processed": 1024, "results": 1024 }
```

#### `POST /api/admin/process-inbox`
inbox/ 의 미처리 mp3 전체를 백그라운드 큐에 등록.

**Response**
```json
{
  "queued": 2,
  "skipped": 0,
  "files": ["recording_a.mp3", "recording_b.mp3"]
}
```

---

### 헬스체크

#### `GET /health`

**Response**
```json
{ "status": "ok", "rerank_ready": true, "rerank_error": null }
```

| 필드 | 설명 |
|------|------|
| rerank_ready | reranker 모델 로드 완료 여부 (백그라운드 로딩) |
| rerank_error | 로드 실패 시 에러 문자열, 정상이면 null |
