package com.recallai.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.recallai.config.GroqProperties;
import com.recallai.config.OpenAiProperties;
import com.recallai.config.RecordingProperties;
import com.recallai.dto.CallSummaryDto;
import com.recallai.dto.SttSegmentDto;
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
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 호텔 PMS 통화 STT 세그먼트 → 화자 분리·구조화 요약 JSON.
 *
 * <p>{@code python-svc/transcribe_summarize.py} 의 {@code summarize()} 자바 이식.
 * provider 분기는 {@code recording.summarize-provider} (groq/openai). 기본 OpenAI, 실패 시 Groq 폴백.
 */
@Service
@RequiredArgsConstructor
public class CallSummarizeService {

    private static final Logger log = LoggerFactory.getLogger(CallSummarizeService.class);

    private final RecordingProperties recProps;
    private final GroqProperties groqProps;
    private final OpenAiProperties openAiProps;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private CloseableHttpClient httpClient;

    @PostConstruct
    private void init() {
        var config = RequestConfig.custom()
                .setConnectTimeout(5000)
                .setSocketTimeout(60_000)
                .build();
        this.httpClient = HttpClients.custom().setDefaultRequestConfig(config).build();
    }

    @PreDestroy
    private void destroy() {
        try { if (httpClient != null) httpClient.close(); } catch (Exception ignored) {}
    }

    /** 통화 컨텍스트 — LLM 메타 프리앰블에 사용. */
    public record SummarizeContext(
            String callerNo, String receiverNo, String callDt,
            String hotelName, String propCd, Set<String> daolNos
    ) {}

    /** 사용된 LLM 모델명 — AIA_CALL_ANALYSIS.LLM_MODEL 컬럼에 기록. */
    public String resolveLlmModel() {
        return "openai".equalsIgnoreCase(recProps.getSummarizeProvider())
                ? openAiProps.getModel()
                : groqProps.getModel();
    }

    /**
     * 세그먼트를 화자분리·구조화 요약 JSON으로 변환.
     * 빈 세그먼트면 null. 프로바이더 둘 다 실패하면 null.
     */
    public CallSummaryDto summarize(List<SttSegmentDto> segments, SummarizeContext ctx) {
        if (segments == null || segments.isEmpty()) {
            log.info("[요약] 빈 세그먼트 — 스킵");
            return null;
        }
        String userInput = buildUserInput(segments, ctx);

        String provider = recProps.getSummarizeProvider() == null
                ? "openai" : recProps.getSummarizeProvider().toLowerCase(Locale.ROOT);

        // primary → fallback 순으로 시도. 둘 다 실패하면 null.
        if ("openai".equals(provider)) {
            CallSummaryDto r = tryOpenAi(userInput);
            return r != null ? r : tryGroq(userInput);
        }
        CallSummaryDto r = tryGroq(userInput);
        return r != null ? r : tryOpenAi(userInput);
    }

    // ────────────────────────────────────────────────────────────
    // OpenAI / Groq 분기 — 동일 프롬프트, URL/key/model만 다름
    // ────────────────────────────────────────────────────────────

    private CallSummaryDto tryOpenAi(String userInput) {
        if (openAiProps.getApiKey() == null || openAiProps.getApiKey().isBlank()) {
            log.debug("[요약-OpenAI] API key 없음 — 스킵");
            return null;
        }
        try {
            return callChat(openAiProps.getUrl(), openAiProps.getApiKey(), openAiProps.getModel(), userInput);
        } catch (Exception e) {
            log.warn("[요약-OpenAI] 실패 — 폴백 시도: {}", e.getMessage());
            return null;
        }
    }

    private CallSummaryDto tryGroq(String userInput) {
        if (groqProps.getApiKey() == null || groqProps.getApiKey().isBlank()) {
            log.debug("[요약-Groq] API key 없음 — 스킵");
            return null;
        }
        try {
            return callChat(groqProps.getUrl(), groqProps.getApiKey(), groqProps.getModel(), userInput);
        } catch (Exception e) {
            log.warn("[요약-Groq] 실패: {}", e.getMessage());
            return null;
        }
    }

    private CallSummaryDto callChat(String url, String apiKey, String model, String userInput) throws Exception {
        var body = Map.of(
                "model", model,
                "messages", List.of(
                        Map.of("role", "system", "content", SUMMARIZE_SYSTEM_PROMPT),
                        Map.of("role", "user",   "content", userInput)
                ),
                "max_tokens", 4000,
                "temperature", 0.2,
                "response_format", Map.of("type", "json_object")
        );

        var post = new HttpPost(url);
        post.setHeader("Authorization", "Bearer " + apiKey);
        post.setHeader("Content-Type", "application/json");

        byte[] jsonBytes = objectMapper.writeValueAsBytes(body);
        var entity = new ByteArrayEntity(jsonBytes);
        entity.setContentType("application/json");
        post.setEntity(entity);

        long started = System.currentTimeMillis();
        try (CloseableHttpResponse response = httpClient.execute(post)) {
            String responseBody = EntityUtils.toString(response.getEntity(), "UTF-8");
            int status = response.getStatusLine().getStatusCode();
            if (status < 200 || status >= 300) {
                throw new RuntimeException("LLM 호출 실패 status=" + status + " body=" + responseBody);
            }
            var root = objectMapper.readTree(responseBody);
            var choices = root.get("choices");
            if (choices == null || choices.isEmpty()) {
                throw new RuntimeException("LLM 응답 choices 없음: " + responseBody);
            }
            String content = choices.get(0).get("message").get("content").asText();
            CallSummaryDto parsed = objectMapper.readValue(content, CallSummaryDto.class);
            log.info("[요약] 모델={} 완료 ({}ms)", model, System.currentTimeMillis() - started);
            return parsed;
        }
    }

    // ────────────────────────────────────────────────────────────
    // 입력 빌드 (Python summarize() 의 user_input 동치)
    // ────────────────────────────────────────────────────────────

    private String buildUserInput(List<SttSegmentDto> segments, SummarizeContext ctx) {
        var lines = new ArrayList<String>();
        for (var seg : segments) {
            int start = (int) seg.getStart();
            String mmss = String.format("%d:%02d", start / 60, start % 60);
            String text = seg.getText() == null ? "" : seg.getText().trim();
            lines.add("[" + mmss + "] " + text);
        }
        String sttText = String.join("\n", lines);

        if (ctx == null) return sttText;

        var meta = new ArrayList<String>();
        meta.add("[통화 메타정보 — 아래 값은 시스템이 확정한 값이므로 report 필드에 그대로 사용할 것]");
        if (notBlank(ctx.hotelName())) {
            meta.add("- 발신 호텔명 (참고용): " + ctx.hotelName()
                    + "  ← speaker_B 역할 파악에만 사용. report의 '문의자'에는 호텔명 쓰지 말고 담당자명·부서명으로 채울 것");
        }
        if (notBlank(ctx.callerNo())) {
            meta.add("- 연락처(발신번호): " + ctx.callerNo() + "  ← report의 '연락처' 줄에 이 값 그대로 사용");
        }
        if (notBlank(ctx.receiverNo())) {
            boolean isDaol = ctx.daolNos() != null && ctx.daolNos().contains(ctx.receiverNo());
            meta.add("- 수신번호: " + ctx.receiverNo() + " (" + (isDaol ? "다올 비전 측" : "상대측") + ")");
        }
        if (ctx.daolNos() != null && !ctx.daolNos().isEmpty()) {
            var sorted = new ArrayList<>(ctx.daolNos()); java.util.Collections.sort(sorted);
            meta.add("- 다올 비전 수신번호 목록: " + String.join(", ", sorted)
                    + "  ← 이 번호들 = 다올 비전 상담원(수신측)");
        }
        if (notBlank(ctx.callDt())) {
            meta.add("- 통화일시: " + ctx.callDt());
        }
        return String.join("\n", meta) + "\n\n" + sttText;
    }

    private static boolean notBlank(String s) { return s != null && !s.isBlank(); }

    // ────────────────────────────────────────────────────────────
    // 시스템 프롬프트 — Python 코드와 1:1 동일 (수정 시 양쪽 동기화 필요)
    // ────────────────────────────────────────────────────────────
    private static final String SUMMARIZE_SYSTEM_PROMPT = """
            너는 호텔 PMS 상담 통화 모노 녹취록을 분석하는 전문가야.

            [중요 컨텍스트]
            - **"다올 비전"** 이 자사(상담 제공자) 이름이야. STT가 이걸 "다월 기자", "다홀 기자", "다월 비전" 등으로 잘못 인식했을 수 있는데, 그런 표현이 보이면 모두 **다올 비전**으로 간주해.
            - 다올 비전 상담원 = 호텔 PMS·키오스크 기술지원 담당 (수신측).
            - 호텔 프런트 직원 = 장애·설정 문의로 전화한 쪽 (발신측).

            모노 녹음이라 발신자/수신자가 하나의 오디오에 섞여 있어.
            대화 맥락(인사말, 문의 시작, 높임말 톤, 질문-답변 패턴)을 보고 **A/B 두 화자로 추정 분리**하고 구조화된 분석을 만들어.

            [입력]
            타임스탬프가 붙은 STT 전사 세그먼트들.

            [출력 — 순수 JSON. 설명·마크다운 금지]
            {
              "report": "⭐ KOK_CALL_MNTR.REPORT에 저장될 값. 아래 형식을 정확히 따를 것 (줄바꿈 포함):\\n문의자 : [발신자의 담당자명 또는 부서명. 호텔명은 절대 쓰지 말 것 (호텔은 별도 관리). 예: '프런트 담당자', '예약실', '김철수 대리'. 파악 안 되면 '미확인']\\n연락처: [통화에서 언급된 전화번호. 없으면 '미확인']\\n문의내역: [발신자 문의 내용을 사실 위주 2~4줄 서술. 상황·증상·요청 포함. 상담원 답변 내용 섞지 마. 호실번호·예약번호 등 구체 정보 그대로 포함]",
              "feedback": "⭐ 상담원이 제공한 답변·조치·후속안내를 통합해 KOK_CALL_MNTR.FEEDBACK 컬럼에 바로 넣을 수 있는 서술형 2~4줄. 예: '세팅값 확인 결과 일부 설정 누락. 수정 후 재전송 안내. 추후 공지사항으로 안내 예정.'",
              "system_cd": "접수시스템 코드. 아래 중 하나만 출력(코드만): PMS(프런트·예약·정산·객실 등 PMS 관련) / KIOSK(키오스크 장비·오류·사용법) / POS(POS 단말·결제) / CMS(CMS 관련) / DBE(부킹엔진·OTA) / ETC(기타)",
              "system_con": "접수내용 코드. system_cd 먼저 판단한 뒤 아래 표에서 가장 잘 맞는 코드 하나만 출력(숫자만).\\n[PMS] 1(예약) 2(프런트) 3(정산) 4(객실정비) 5(일마감) 8(업장관리) 9(메뉴관리) 10(예약관리) 27(정보관리) 28(상품/판매관리) 29(예약확인) 49(사용처리관련) 50(매출관련) 51(객실관리관련)\\n[KIOSK] 24(에러/조회) 26(사용법)\\n[DBE] 25(OTA예약)\\n[ETC] 26(사용법)",
              "system_tp": "접수유형 코드. 아래 중 하나만 출력(코드만): 01(신규기능 요청) / 02(시스템장애·오류) / 03(단순문의·사용법) / 04(칭찬) / 05(불만)",
              "urgency_cd": "긴급도 코드. 아래 중 하나만 출력(알파벳만): A(심각: 전체 사용불가·데이터손실·즉시처리필요) / B(중요: 일부기능불가·업무지연) / C(보통: 불편하지만업무가능·일반장애) / D(낮음: 단순문의·설정변경·사용법질문)",
              "status": "해결됨 / 처리중 / 추가조사 필요 / 미확인 중 하나",
              "category": "키오스크 / 객실키 / 결제·카드 / 체크인 / PMS기능 / 부킹엔진 / 소모품·시재 / 기타 중 하나",
              "question": "발신자의 핵심 문의 (50자 이내, 의문문)",
              "context": "질문 배경·상황 (1-2줄)",
              "answer_given": "상담원이 제공한 답변·조치 (없으면 '답변 보류')",
              "actions_taken": ["실제 취해진 조치 리스트"],
              "follow_up": "이후 처리 예정 사항 (없으면 '없음')",
              "hotel_nm": "통화에서 언급된 발신 호텔·업장명. STT 텍스트나 화자 발화에서 들린 그대로. 예: '홈즈스테이', '가산 그래비티', '수원 노보텔'. 전혀 알 수 없으면 null",
              "caller_nm": "문의자명+직함. 통화에서 파악된 이름·직책 조합. 예: '김정자 지배인', '박철수 대리', '예약실 담당자'. 이름도 직함도 전혀 모를 때만 '미확인'",
              "speaker_A": "역할 추정 (예: '다올 비전 상담원 (수신측)')",
              "speaker_B": "역할 추정 (예: '홈즈스테이 예약실 직원 (발신측)')",
              "inquirer": "A or B",
              "responder": "A or B",
              "summary": "통화 전체를 상세히 서술. 발신자 문의 배경·증상·요청 → 상담원 확인·조치 → 결론·후속사항 순서로 5~10줄 자세한 한국어 서술형 요약. 핵심 조치와 미결 사항은 반드시 포함.",
              "dialogue": [
                {"speaker": "A", "start": 0.0, "text": "정리된 발화"},
                {"speaker": "B", "start": 3.5, "text": "..."}
              ]
            }

            [report·feedback 필드 지침]
            - **report**: 반드시 아래 3줄 형식 유지. DB에 그대로 저장됨.
              ```
              문의자 : 홈즈스테이 예약실
              연락처: 미확인
              문의내역: 부킹엔진 판매 페이지에 신규 패키지 상품 설명란 미노출. 수원·가산 지점만 해당.
              ```
            - **feedback**: 상담원이 실제로 말한 조치·답변·안내만. 문의자 발화 섞지 마. 상담원 답변이 없거나 보류면 '답변 보류 — 확인 후 재연락 예정' 식으로.
            - 두 필드만 있어도 **원본 상담 기록을 재구성**할 수 있어야 함 (나머지 필드는 분석·검색용).

            [규칙]
            1. **dialogue는 마지막 필드**. 대화 흐름을 보여주는 핵심 턴 5~15개로 축약. 입력 세그먼트가 50개든 100개든 전부 담지 마. 긴 발화는 1~2줄로 요약해서 화자별 핵심 의도만 남기고, 단순 맞장구("네네")는 생략.
            2. 화자 판별 단서:
               - 전화 건 쪽 = 인사말 뒤 곧바로 용건 꺼냄 ("홈즈스테이 예약실인데요", "저희가 뭐 여쭤볼 게 있어서")
               - 받은 쪽 = 회사명 짧게 밝힘 ("다월입니다"), 질문 주도 ("어떤 상품일까요?")
            3. 호실번호, 전화번호, 예약번호, 이름 등 통화에서 언급된 구체 정보는 **그대로** 기록해. 지우거나 마스킹하지 마. 호텔 브랜드명은 역할 추정용 한 번만 사용 후 이후엔 '해당 업장' 등으로 일반화 가능.
            4. STT 인식 오류 가능성 — 문맥으로 판단해 자연스럽게 정리. 단, 없는 내용은 지어내지 마.
            5. 불명확한 부분은 "미확인"으로.
            6. **system_cd, system_con, system_tp, urgency_cd, question, answer_given, summary 등 모든 분석 필드는 반드시 채워**. "(미확인)"은 정말 파악 안 될 때만 사용.
            7. question / answer_given 은 각각 **inquirer / responder 발화에 근거**해 작성.""";
}
