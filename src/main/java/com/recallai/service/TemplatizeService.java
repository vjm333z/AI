package com.recallai.service;

import java.util.Map;

/**
 * HyDE 하이브리드 템플릿 추출기의 공통 인터페이스.
 *
 * <p>구현체는 LLM provider별로 (Groq / Claude / Gemini / OpenAI ...) 분리하며,
 * {@code rag.templatize.provider} 설정값으로 런타임 교체 가능.
 *
 * <p>반환 JSON 계약 (키 고정):
 * <ul>
 *   <li>{@code core_question} — 반드시 '?'로 끝나는 의문문, 50자 이내</li>
 *   <li>{@code situation} — 질문 발생 맥락, 50자 이내</li>
 *   <li>{@code cause} — FEEDBACK 기반 원인 (없으면 "원인 미확인")</li>
 *   <li>{@code solution} — FEEDBACK 기반 조치</li>
 * </ul>
 * 실패(네트워크·파싱·한도 초과) 시 {@code null} 반환. 호출측에서 재시도·스킵 판단.
 */
public interface TemplatizeService {

    /** 원본 REPORT + FEEDBACK을 HyDE 4필드 JSON으로 추출. 실패 시 null. */
    Map<String, Object> templatize(String report, String feedback);

    /** 로그·모니터링용 provider 이름 (groq / claude / gemini 등). */
    String providerName();
}
