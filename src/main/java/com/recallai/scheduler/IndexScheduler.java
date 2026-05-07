package com.recallai.scheduler;

import com.recallai.config.RagProperties;
import com.recallai.service.IndexService;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 실패 적재 자동 복구.
 * indexSingle이 실패하면 failed_index.txt에 자동 기록 → 본 스케줄러가 일괄 재시도.
 * 기본값: 매일 새벽 3시 (서버 로컬 타임존 기준). rag.scheduler.enabled=false 로 끌 수 있음.
 *
 * <p>{@code @Scheduled(cron=...)} 는 컴파일 타임 상수만 받아서 SpEL 또는 ${} placeholder만 가능.
 * 그래서 cron만 application.yml 직접 참조 (RagProperties.scheduler.cron 와 동일 값).
 */
@Component
@RequiredArgsConstructor
public class IndexScheduler {

    private static final Logger log = LoggerFactory.getLogger(IndexScheduler.class);

    private final IndexService indexService;
    private final RagProperties props;

    @Scheduled(cron = "${rag.scheduler.cron:0 0 3 * * *}")
    public void dailyRetryFailed() {
        if (!props.getScheduler().isEnabled()) {
            log.debug("스케줄러 비활성화 상태, 실패 재시도 스킵");
            return;
        }
        try {
            log.info("실패 적재 재시도 시작");
            var result = indexService.retryFailed();
            log.info("실패 적재 재시도 완료: {}", result);
        } catch (Exception e) {
            log.error("실패 적재 재시도 실패", e);
        }
    }
}
