package com.recallai.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.recallai.config.OpenAiProperties;
import com.recallai.util.TextSanitizer;
import lombok.RequiredArgsConstructor;
import org.apache.http.client.config.RequestConfig;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.entity.ByteArrayEntity;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.util.EntityUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * OpenAI gpt-4o-mini.
 * - SearchService.ask() 의 Groq 폴백 (RAG 답변 생성)
 * - TemplatizeService 구현체 (bean name "openai") → rag.templatize.provider=openai 일 때 HyDE 적재에 사용
 */
@Service("openai")
@RequiredArgsConstructor
public class OpenAiService implements TemplatizeService {

    @Override
    public String providerName() { return "openai"; }

    private static final Logger log = LoggerFactory.getLogger(OpenAiService.class);

    private final OpenAiProperties props;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private CloseableHttpClient httpClient;

    @PostConstruct
    private void init() {
        var config = RequestConfig.custom()
                .setConnectTimeout(5000)
                .setSocketTimeout(props.getTimeoutMs())
                .build();
        this.httpClient = HttpClients.custom().setDefaultRequestConfig(config).build();
    }

    @PreDestroy
    private void destroy() {
        try { if (httpClient != null) httpClient.close(); } catch (Exception ignored) {}
    }

    private static final String SYSTEM_PROMPT = """
            너는 호텔 PMS 고객 문의에 과거 사례를 참고해 답변하는 전문 도우미야.

            [답변 기준 — 반드시 지켜]
            1. 각 과거 사례의 유사도·재정렬점수를 보고 참고 여부를 판단해:
               - 85%+: 매우 유사. 해당 사례의 해결 방식을 적극 참고해 구체적으로 답변.
               - 70~85%: 유사. '유사 사례를 바탕으로 추정하면' 같은 표현으로 참고.
               - 70% 미만: 관련성 낮음. 이 사례는 무시하고 언급도 하지 마.
            2. 질문과 관련 없어 보이는 사례는 인용하지 마. 억지로 끼워맞추지 마.
            3. 과거 사례에 실제 해결 방법이 있으면 그대로 안내하고, 없거나 모호하면 '담당자 확인이 필요합니다'.
            4. '처리 완료'·'해결 완료' 같은 단정적 표현 금지. 과거 사례에서 실제 있었던 조치만 인용.
            5. 전달된 모든 사례가 70% 미만이거나 질문과 무관하면 '관련 사례를 찾지 못했습니다. 담당자에게 전달하겠습니다.'로만 답해.
            6. 반드시 한국어로만 작성. 한자(漢字)·일본어·중국어 문자 절대 사용 금지.
            7. 3~5줄 이내.
            """;

    public String ask(String question, List<Map<String, Object>> similarCases) throws Exception {
        if (similarCases == null || similarCases.isEmpty()) {
            return "관련 사례를 찾지 못했습니다. 담당자에게 전달하겠습니다.";
        }

        var context = new StringBuilder("=== 유사 과거 사례 ===\n\n");
        for (int i = 0; i < similarCases.size(); i++) {
            var caseData = similarCases.get(i);
            @SuppressWarnings("unchecked")
            var payload = (Map<String, Object>) caseData.get("payload");
            var score = ((Number) caseData.get("score")).doubleValue();
            var rerankScore = caseData.get("rerank_score");

            context.append("[사례 ").append(i + 1).append("] Qdrant 유사도: ")
                   .append(String.format("%.1f%%", score * 100));
            if (rerankScore instanceof Number n) {
                context.append(" | Reranker 관련도: ")
                       .append(String.format("%.1f%%", n.doubleValue() * 100));
            }
            context.append("\n");
            context.append("문제: ").append(TextSanitizer.cleanForPrompt(payload.get("report"))).append("\n");
            context.append("해결: ").append(TextSanitizer.cleanForPrompt(payload.get("feedback"))).append("\n\n");
        }
        String userPrompt = context + "=== 현재 문의 ===\n" + question;

        var post = new HttpPost(props.getUrl());
        post.setHeader("Authorization", "Bearer " + props.getApiKey());
        post.setHeader("Content-Type", "application/json");

        var body = Map.of(
            "model", props.getModel(),
            "messages", List.of(
                Map.of("role", "system", "content", SYSTEM_PROMPT),
                Map.of("role", "user",   "content", userPrompt)
            ),
            "max_tokens", 500
        );

        var jsonBytes = objectMapper.writeValueAsBytes(body);
        var entity = new ByteArrayEntity(jsonBytes);
        entity.setContentType("application/json");
        post.setEntity(entity);

        try (CloseableHttpResponse response = httpClient.execute(post)) {
            var responseBody = EntityUtils.toString(response.getEntity(), "UTF-8");
            int status = response.getStatusLine().getStatusCode();
            if (status < 200 || status >= 300) {
                throw new RuntimeException("OpenAI 호출 실패 status=" + status + " body=" + responseBody);
            }
            log.debug("OpenAI 응답: {}", responseBody);
            var root = objectMapper.readTree(responseBody);
            var choices = root.get("choices");
            if (choices == null || choices.isEmpty()) {
                throw new RuntimeException("OpenAI 응답에 choices 없음: " + responseBody);
            }
            return choices.get(0).get("message").get("content").asText();
        }
    }

    // ============================================================
    // HyDE 하이브리드 템플릿화
    //   GroqService.templatize()와 동일 프롬프트·계약. provider만 OpenAI로 분기.
    // ============================================================
    private static final String TEMPLATIZE_SYSTEM_PROMPT = """
            너는 호텔 PMS 운영팀 내부 기술지원 티켓을 RAG 검색용 구조로 변환하는 분석가야.

            [입력]
            과거 상담 티켓의 REPORT(문의 본문)과 FEEDBACK(답변)을 줄게.

            [출력 - 순수 JSON. 설명·마크다운 금지]
            {
              "core_question": "티켓이 본질적으로 묻는 질문. 반드시 물음표(?)로 끝나는 의문문. 50자 이내.",
              "situation": "질문 발생 맥락을 1줄로 (50자 이내)",
              "cause": "FEEDBACK에서 추정된 원인 (1-2줄, 없으면 원인 미확인)",
              "solution": "FEEDBACK에 기록된 실제 조치 (1-2줄, 구체적으로)"
            }

            [규칙]
            1. core_question은 반드시 물음표(?)로 끝나는 의문문 형태로 작성.
               핵심 현상·키워드 1~2개를 포함해 유사 이슈끼리 구분 가능하게 해.
               나쁜 예: "객실키 발급이 안돼요" (서술문), "객실키 발급 오류" (너무 일반적)
               좋은 예: "객실키 디스펜서 포트 연결 불량 조치 방법?", "카드 승인 실패 시 바탕화면 튕김 원인?"
            2. PII(이름·연락처·예약번호·호텔명·^로 마스킹된 이름·구체 호실번호)는 전부 제거하거나 일반화.
               특히 "\\d+호" 패턴(예: 410호, 1809호)은 반드시 제거. 일반화하려면 "객실"로만.
               전화번호(010-XXXX), 5자리 이상 숫자(예약번호 추정)도 제거.
            3. cause/solution 구분은 의미 기준. FEEDBACK에 두 내용이 혼재돼있으면 나눠.
            4. 원본 상담사 말투·인사말·감사표현(^확인 감사합니다, ㅇㅇ대리 감사합니다) 전부 제거.
            5. solution은 FEEDBACK에 있는 실제 조치만 인용. REPORT의 "조치 >>" 블록은 상담사의 1차 시도일 뿐이므로 완전히 무시.
               solution과 cause 안의 어휘는 반드시 FEEDBACK 텍스트에 실제 등장한 단어여야 함.
               REPORT에만 있고 FEEDBACK엔 없는 표현은 절대 쓰지 마.
               FEEDBACK에 명확한 해결이 없으면 solution은 짧게 "담당 확인 중", "검토 예정" 형태.
            6. 공지사항 번호(예: 224번), 구체 장비 번호(예: 키오스크 2번)는 일반화하거나 제거.""";

    /**
     * REPORT + FEEDBACK → HyDE 4필드 JSON.
     * 실패(네트워크·파싱·타임아웃) 시 null 반환 → 호출측에서 스킵·재시도 판단.
     */
    @Override
    public Map<String, Object> templatize(String report, String feedback) {
        String userMsg = "REPORT: " + (report == null ? "" : report) + "\n\n"
                       + "FEEDBACK: " + (feedback == null ? "" : feedback);

        var body = Map.of(
            "model", props.getModel(),
            "messages", List.of(
                Map.of("role", "system", "content", TEMPLATIZE_SYSTEM_PROMPT),
                Map.of("role", "user", "content", userMsg)
            ),
            "max_tokens", 500,
            "temperature", 0.2,
            "response_format", Map.of("type", "json_object")
        );

        var post = new HttpPost(props.getUrl());
        post.setHeader("Authorization", "Bearer " + props.getApiKey());
        post.setHeader("Content-Type", "application/json");

        try {
            var jsonBytes = objectMapper.writeValueAsBytes(body);
            var entity = new ByteArrayEntity(jsonBytes);
            entity.setContentType("application/json");
            post.setEntity(entity);

            try (CloseableHttpResponse response = httpClient.execute(post)) {
                var responseBody = EntityUtils.toString(response.getEntity(), "UTF-8");
                int status = response.getStatusLine().getStatusCode();
                if (status < 200 || status >= 300) {
                    log.warn("OpenAI templatize 실패 status={} body={}", status, responseBody);
                    return null;
                }
                var root = objectMapper.readTree(responseBody);
                var choices = root.get("choices");
                if (choices == null || choices.isEmpty()) return null;
                var content = choices.get(0).get("message").get("content").asText();
                var parsed = objectMapper.readTree(content);
                var result = new LinkedHashMap<String, Object>();
                for (String k : new String[]{"core_question", "situation", "cause", "solution"}) {
                    JsonNode v = parsed.get(k);
                    result.put(k, v != null && !v.isNull() ? v.asText().trim() : "");
                }
                return result;
            }
        } catch (Exception e) {
            log.warn("OpenAI templatize 예외: {}", e.getMessage());
            return null;
        }
    }
}
