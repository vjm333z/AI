package com.recallai.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.recallai.dto.KokCallMntrDto;
import com.recallai.repository.KokCallMntrMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import javax.annotation.PostConstruct;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * KOK_CALL_MNTR.REPORT에서 "연락처: 010-..." 파싱 → phone_lookup.json 생성.
 * { "01012345678": "0000000061", ... } 형태로 저장.
 * STT 스크립트가 발신번호 → prop_cd 매칭에 사용.
 */
@Service
public class PhoneLookupService {

    private static final Logger log = LoggerFactory.getLogger(PhoneLookupService.class);

    // "연락처: 010-1234-5678" 또는 "연락처: 01012345678" 등 다양한 형식 대응
    private static final Pattern PHONE_PATTERN =
            Pattern.compile("연락처\\s*[:\\-]?\\s*([0-9][0-9\\-]{7,12}[0-9])");

    @Autowired
    private KokCallMntrMapper kokCallMntrMapper;

    @Value("${rag.data-dir:.}")
    private String dataDir;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @PostConstruct
    public void init() {
        try {
            refresh();
        } catch (Exception e) {
            log.warn("phone_lookup 초기 생성 실패 (DB 미기동 가능성): {}", e.getMessage());
        }
    }

    /** 매일 새벽 2시 자동 갱신 */
    @Scheduled(cron = "0 0 2 * * *")
    public void scheduled() {
        try {
            refresh();
        } catch (Exception e) {
            log.warn("phone_lookup 갱신 실패: {}", e.getMessage());
        }
    }

    public Map<String, String> refresh() throws Exception {
        List<KokCallMntrDto> rows = kokCallMntrMapper.selectForPhoneLookup();

        Map<String, String> lookup = new HashMap<>();
        for (KokCallMntrDto row : rows) {
            String propCd = row.getPropCd();
            String report = row.getReport();
            if (propCd == null || report == null) continue;

            Matcher m = PHONE_PATTERN.matcher(report);
            while (m.find()) {
                // 숫자만 추출 (하이픈 제거)
                String digits = m.group(1).replaceAll("[^0-9]", "");
                if (digits.length() >= 9) {
                    lookup.putIfAbsent(digits, propCd);
                }
            }
        }

        Path path = Paths.get(dataDir, "phone_lookup.json");
        objectMapper.writerWithDefaultPrettyPrinter().writeValue(path.toFile(), lookup);
        log.info("phone_lookup.json 저장 완료: {}개 번호 → {}", lookup.size(), path.toAbsolutePath());
        return lookup;
    }
}
