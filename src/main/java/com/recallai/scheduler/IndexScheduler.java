package com.recallai.scheduler;

import com.recallai.service.RagService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 증분 적재 자동 실행.
 * 기본값: 매일 새벽 3시 (서버 로컬 타임존 기준).
 * rag.scheduler.enabled=false 로 끌 수 있음.
 */
@Component
public class IndexScheduler {

    private static final Logger log = LoggerFactory.getLogger(IndexScheduler.class);

    @Autowired
    private RagService ragService;

    @Value("${rag.scheduler.enabled:true}")
    private boolean enabled;

    @Scheduled(cron = "${rag.scheduler.cron:0 0 3 * * *}")
    public void dailyIncrementalIndex() {
        if (!enabled) {
            log.debug("스케줄러 비활성화 상태, 증분 적재 스킵");
            return;
        }
        try {
            log.info("증분 적재 시작");
            String result = ragService.indexUpdated();
            log.info("증분 적재 완료: {}", result);
        } catch (Exception e) {
            log.error("증분 적재 실패", e);
        }
    }
}
