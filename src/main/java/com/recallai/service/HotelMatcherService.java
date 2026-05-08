package com.recallai.service;

import com.recallai.dto.ComplexDto;
import com.recallai.dto.HotelDto;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * 통화 발/수신번호 + STT 텍스트로 호텔/컴플렉스 매칭.
 *
 * <p>{@code python-svc/hotel_matcher.py} 의 자바 이식판. 의존: {@link HotelCacheService} (in-memory 캐시).
 * fuzzy 매칭은 Python {@code difflib.SequenceMatcher.ratio()} 와 동일한
 * Ratcliff-Obershelp 알고리즘을 직접 구현 (외부 의존 없음).
 */
@Service
@RequiredArgsConstructor
public class HotelMatcherService {

    private static final Logger log = LoggerFactory.getLogger(HotelMatcherService.class);

    /** Python 코드와 동일한 fuzzy 매칭 임계값. */
    private static final double FUZZY_THRESHOLD = 0.65;

    private final HotelCacheService hotelCache;

    // ────────────────────────────────────────────────────────────
    // 매칭 결과 컨테이너
    // ────────────────────────────────────────────────────────────
    public record HotelMatch(HotelDto hotel, ComplexDto complex, String aliasCandidate) {
        public static HotelMatch none() { return new HotelMatch(null, null, null); }
        public boolean isFound() { return hotel != null; }
    }

    // ────────────────────────────────────────────────────────────
    // 1) 전화번호 매칭 (cmpxReprTel + mobileNos)
    // ────────────────────────────────────────────────────────────

    /** 발/수신번호로 호텔 + 컴플렉스 매칭. 매칭 실패 시 {@link HotelMatch#none()}. */
    public HotelMatch findByCallNo(String callNo) {
        if (callNo == null || callNo.isBlank()) return HotelMatch.none();
        String normalized = callNo.replaceAll("\\D", "");
        if (normalized.isEmpty()) return HotelMatch.none();

        for (HotelDto h : hotelCache.getAllHotels()) {
            for (ComplexDto c : safeComplexes(h)) {
                String reprTel = digitsOnly(c.getCmpxReprTel());
                if (!reprTel.isEmpty() && reprTel.equals(normalized)) {
                    return new HotelMatch(h, c, null);
                }
                if (c.getMobileNos() == null) continue;
                for (String mobile : c.getMobileNos()) {
                    if (digitsOnly(mobile).equals(normalized)) {
                        return new HotelMatch(h, c, null);
                    }
                }
            }
        }
        return HotelMatch.none();
    }

    // ────────────────────────────────────────────────────────────
    // 2) 호텔명 오인식 보정 (alias → propShrtNm 치환)
    // ────────────────────────────────────────────────────────────

    /**
     * STT 텍스트의 호텔명 오인식을 정식명으로 치환.
     * alias가 긴 것부터 적용해 부분치환 오버랩 방지.
     */
    public String fixHotelNames(String text) {
        if (text == null || text.isEmpty()) return text;
        Collection<HotelDto> hotels = hotelCache.getAllHotels();
        if (hotels.isEmpty()) return text;

        // (alias, hotel) 쌍을 alias 길이 내림차순으로 정렬
        List<Map.Entry<String, HotelDto>> pairs = new ArrayList<>();
        for (HotelDto h : hotels) {
            if (h.getAliases() == null) continue;
            for (String alias : h.getAliases()) {
                if (alias != null && !alias.isEmpty()) {
                    pairs.add(new AbstractMap.SimpleEntry<>(alias, h));
                }
            }
        }
        pairs.sort(Comparator.comparingInt((Map.Entry<String, HotelDto> e) -> e.getKey().length()).reversed());

        String result = text;
        for (Map.Entry<String, HotelDto> e : pairs) {
            String shrtNm = e.getValue().getPropShrtNm();
            result = result.replace(e.getKey(), shrtNm != null ? shrtNm : e.getKey());
        }
        return result;
    }

    // ────────────────────────────────────────────────────────────
    // 3) 텍스트 매칭 (정식명 → alias → fuzzy)
    // ────────────────────────────────────────────────────────────

    /**
     * 텍스트에서 호텔 매칭: 정확 → 부분 포함 → fuzzy (threshold 0.65).
     * fuzzy 성공 시 {@link HotelMatch#aliasCandidate()}에 매칭 후보 문자열을 반환 — 호출측에서 alias 자동 등록 가능.
     */
    public HotelMatch findFromText(String text) {
        if (text == null || text.isEmpty()) return HotelMatch.none();
        Collection<HotelDto> hotels = hotelCache.getAllHotels();
        if (hotels.isEmpty()) return HotelMatch.none();

        // 1차: 정식명(짧/긴) 정확 포함
        for (HotelDto h : hotels) {
            for (String name : List.of(safe(h.getPropShrtNm()), safe(h.getPropFullNm()))) {
                if (!name.isEmpty() && text.contains(name)) {
                    return new HotelMatch(h, null, null);
                }
            }
        }

        // 2차: 등록 alias 정확 포함
        for (HotelDto h : hotels) {
            if (h.getAliases() == null) continue;
            for (String alias : h.getAliases()) {
                if (alias != null && !alias.isEmpty() && text.contains(alias)) {
                    return new HotelMatch(h, null, null);
                }
            }
        }

        // 3차: fuzzy 슬라이딩 윈도우
        HotelDto best = null;
        double bestScore = FUZZY_THRESHOLD;
        String bestWindow = null;
        for (HotelDto h : hotels) {
            String name = safe(h.getPropShrtNm());
            if (name.isEmpty()) continue;
            int winLen = name.length() + 4;
            int upper = Math.max(1, text.length() - winLen + 1);
            for (int i = 0; i < upper; i++) {
                String window = text.substring(i, Math.min(i + winLen, text.length()));
                double score = ratcliffObershelpRatio(name, window);
                if (score > bestScore) {
                    bestScore = score;
                    best = h;
                    bestWindow = window.trim();
                }
            }
        }
        return best != null ? new HotelMatch(best, null, bestWindow) : HotelMatch.none();
    }

    /** 호텔의 complex 중 텍스트에 언급된 것 (긴 이름 우선). */
    public ComplexDto findCmpxFromText(String text, HotelDto hotel) {
        if (text == null || text.isEmpty() || hotel == null) return null;
        List<ComplexDto> sorted = new ArrayList<>(safeComplexes(hotel));
        sorted.sort(Comparator.comparingInt((ComplexDto c) -> safe(c.getCmpxNm()).length()).reversed());
        for (ComplexDto c : sorted) {
            String nm = safe(c.getCmpxNm());
            if (!nm.isEmpty() && text.contains(nm)) return c;
        }
        return null;
    }

    /** fuzzy 매칭으로 학습된 alias를 hotels.json에 영속화. */
    public void learnAlias(String propCd, String aliasCandidate) {
        if (hotelCache.addAlias(propCd, aliasCandidate)) {
            log.info("[호텔] fuzzy 매칭으로 alias 학습: propCd={} ← '{}'", propCd, aliasCandidate);
        }
    }

    // ────────────────────────────────────────────────────────────
    // 4) 헬퍼 — Ratcliff-Obershelp ratio (Python difflib와 동치)
    // ────────────────────────────────────────────────────────────

    /**
     * Python {@code SequenceMatcher.ratio()} 와 동일.
     * {@code 2 * matching_blocks_total / (len(a) + len(b))}.
     * 0(완전 불일치) ~ 1(완전 일치). 길이 0 입력은 0.
     */
    static double ratcliffObershelpRatio(String a, String b) {
        if (a == null || b == null || a.isEmpty() || b.isEmpty()) return 0.0;
        int matched = matchingBlocksTotal(a, b);
        return 2.0 * matched / (a.length() + b.length());
    }

    /** 가장 긴 공통 부분문자열을 찾고, 그 양옆 부분에서 재귀적으로 다시 찾아 합산. */
    private static int matchingBlocksTotal(String a, String b) {
        if (a.isEmpty() || b.isEmpty()) return 0;
        int[] lcs = longestCommonSubstring(a, b);
        int i = lcs[0], j = lcs[1], n = lcs[2];
        if (n == 0) return 0;
        return n
                + matchingBlocksTotal(a.substring(0, i), b.substring(0, j))
                + matchingBlocksTotal(a.substring(i + n), b.substring(j + n));
    }

    /** 가장 긴 공통 부분문자열의 (a 시작 인덱스, b 시작 인덱스, 길이) 반환. */
    private static int[] longestCommonSubstring(String a, String b) {
        int m = a.length(), n = b.length();
        int[] prev = new int[n + 1];
        int[] curr = new int[n + 1];
        int bestLen = 0, bestI = 0, bestJ = 0;
        for (int i = 1; i <= m; i++) {
            for (int j = 1; j <= n; j++) {
                if (a.charAt(i - 1) == b.charAt(j - 1)) {
                    curr[j] = prev[j - 1] + 1;
                    if (curr[j] > bestLen) {
                        bestLen = curr[j];
                        bestI = i - curr[j];
                        bestJ = j - curr[j];
                    }
                } else {
                    curr[j] = 0;
                }
            }
            int[] tmp = prev; prev = curr; curr = tmp;
            // curr 다음 row 재사용 전 초기화
            for (int k = 0; k <= n; k++) curr[k] = 0;
        }
        return new int[]{bestI, bestJ, bestLen};
    }

    private static String digitsOnly(String s) {
        return s == null ? "" : s.replaceAll("\\D", "");
    }

    private static String safe(String s) { return s == null ? "" : s; }

    private static List<ComplexDto> safeComplexes(HotelDto h) {
        return h.getComplexes() == null ? List.of() : h.getComplexes();
    }
}
