# API 명세서

베이스 URL: `http://localhost:8080` (RAG 앱), `http://localhost:8001` (STT 서버)

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
| mode | string | - | `default`(기본) \| `templated`(HyDE 컬렉션) |

**Response**
```json
{
  "success": true,
  "mode": "default",
  "answer": "체크인 시간은 ...",
  "faq": [
    { "question": "FAQ 질문", "answer": "FAQ 답변" }
  ],
  "sources": [
    {
      "seqNo": 123,
      "propCd": "H001",
      "title": "문의 제목",
      "report": "문의 원문",
      "feedback": "답변 원문",
      "score": 0.87
    }
  ],
  "elapsed_ms": 420
}
```

#### `POST /api/rag/search`
LLM 없이 Qdrant 검색 결과만 반환 (디버그·튜닝용).

**Request** — `/ask`와 동일
**Response**
```json
{
  "success": true,
  "original_question": "...",
  "search_query": "...",
  "qdrant_hits": 10,
  "after_threshold": 5,
  "final": [ /* sources 배열 */ ]
}
```

---

### 인덱싱

#### `POST /api/rag/index`
MariaDB 전체 데이터를 Qdrant에 적재. 최초 1회 또는 컬렉션 리셋 후 실행. (~40분)

**Response**
```json
{ "success": true, "message": "완료: 성공 12500건 / 실패 3건 / 빈본문 12건" }
```

#### `POST /api/rag/index/updated`
`last_sync.txt` 기준 이후 변경분만 증분 적재.

**Response** — `/index`와 동일

#### `POST /api/rag/index/single/{seqNo}`
단건 즉시 인덱싱. KOK_CALL_MNTR 저장 직후 호출용.

| 파라미터 | 위치 | 설명 |
|---------|------|------|
| seqNo | path | KOK_CALL_MNTR.SEQ_NO |

#### `POST /api/rag/index/templated`
HyDE 방식 템플릿 적재 (`inquiry_templated` 컬렉션).

#### `POST /api/rag/index/faq`
`faq.json` 내용을 Qdrant에 적재 (type=FAQ).

#### `POST /api/rag/index/set-types`
기존 REAL 포인트에 `type=REAL` payload 일괄 설정 (재임베딩 없음).

#### `POST /api/rag/index/retry-failed`
`failed_index.txt`에 기록된 실패 건 재시도. 성공 시 장부에서 제거.

#### `GET /api/rag/index/failed`
실패 장부 조회. 건수·사유별 집계·샘플 10건 반환.

---

### 분석·관리

#### `POST /api/rag/analyze-categories`
Qdrant 적재 데이터 샘플링 → Groq로 카테고리 체계 제안.

**Request**
```json
{ "sampleSize": 150 }
```
| 필드 | 타입 | 설명 |
|------|------|------|
| sampleSize | int | 샘플 수 (10~300, 기본 150) |

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
  "reranker": { "available": true, "body": "{\"status\":\"ok\"}" }
}
```

---

## RAG 앱 `/api/queue`

STT 처리 결과가 쌓이는 CALL_QUEUE 관리.

#### `GET /api/queue`
목록 조회.

| 파라미터 | 기본값 | 설명 |
|---------|--------|------|
| status | PENDING | `PENDING` \| `REGISTERED` \| `SKIPPED` |
| page | 0 | 페이지 번호 |
| size | 20 | 페이지 크기 |

**Response**
```json
{
  "success": true,
  "count": 5,
  "items": [
    {
      "callId": 1,
      "propCd": "H001",
      "cmpxCd": "C001",
      "callerNm": "홈즈스테이 예약실",
      "contactNo": "02-1234-5678",
      "sttSummary": "체크인 시간 변경 문의",
      "category": "체크인",
      "resolveStatus": "해결됨",
      "status": "PENDING"
    }
  ]
}
```

#### `GET /api/queue/{callId}`
단건 조회.

#### `PUT /api/queue/{callId}`
수정.

**Request**
```json
{
  "sttReport": "문의내역 수정 내용",
  "sttFeedback": "답변 수정 내용",
  "propCd": "H001",
  "cmpxCd": "C001",
  "category": "체크인",
  "resolveStatus": "해결됨"
}
```

#### `POST /api/queue/{callId}/approve`
승인 → KOK_CALL_MNTR INSERT + Qdrant 인덱싱 + status=REGISTERED.

**Request**
```json
{ "regId": "사번" }
```

#### `POST /api/queue/{callId}/skip`
스킵 → status=SKIPPED.

---

## STT 서버 (`:8001`)

#### `POST /api/recording`
녹음 파일 업로드 → 백그라운드에서 STT + 요약 + CALL_QUEUE 저장.

**Request** — `multipart/form-data`

| 필드 | 타입 | 필수 | 설명 |
|------|------|------|------|
| file | binary | ✅ | mp3 등 오디오 파일 |
| filename | string | - | 파일명 (없으면 업로드 파일명 사용) |

**Response**
```json
{ "status": "queued", "filename": "recording_20260427.mp3" }
```

#### `GET /api/recording/exists`
파일 중복 여부 확인.

| 파라미터 | 설명 |
|---------|------|
| filename | 확인할 파일명 |

**Response**
```json
{ "exists": true }
```

#### `GET /api/admin/status`
STT 서버 처리 현황 조회 (inbox 대기, 최근 처리 결과 등).

#### `GET /api/admin/recent`
최근 처리 결과 목록.

| 파라미터 | 기본값 | 설명 |
|---------|--------|------|
| limit | 20 | 조회 건수 |

#### `GET /api/queue`
CALL_QUEUE 조회 (STT 서버 직접 조회).

| 파라미터 | 기본값 | 설명 |
|---------|--------|------|
| status | PENDING | `PENDING` \| `REGISTERED` \| `SKIPPED` |
| limit | 50 | 조회 건수 |

#### `PUT /api/queue/{call_id}/status`
CALL_QUEUE 상태 변경.

**Request**
```json
{ "status": "SKIPPED" }
```

#### `GET /api/queue/caller-history`
발신자 번호 기준 통화 이력 조회.

| 파라미터 | 기본값 | 설명 |
|---------|--------|------|
| phone | - | 발신자 번호 |
| exclude_id | - | 제외할 call_id |
| limit | 10 | 조회 건수 |

#### `GET /health`
STT 서버 헬스체크.

**Response**
```json
{ "status": "ok" }
```
