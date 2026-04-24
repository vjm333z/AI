package com.recallai.controller;

import com.recallai.dto.CallQueueDto;
import com.recallai.service.CallQueueService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/queue")
public class CallQueueController {

    private static final Logger log = LoggerFactory.getLogger(CallQueueController.class);

    @Autowired
    private CallQueueService callQueueService;

    /** GET /api/queue?status=PENDING&page=0&size=20 */
    @GetMapping
    public Map<String, Object> list(
            @RequestParam(defaultValue = "PENDING") String status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        Map<String, Object> result = new HashMap<>();
        try {
            List<CallQueueDto> items = callQueueService.list(status, page, size);
            result.put("success", true);
            result.put("items", items);
            result.put("count", items.size());
        } catch (Exception e) {
            log.error("CALL_QUEUE 목록 조회 실패", e);
            result.put("success", false);
            result.put("message", e.getMessage());
        }
        return result;
    }

    /** GET /api/queue/{callId} */
    @GetMapping("/{callId}")
    public Map<String, Object> get(@PathVariable Long callId) {
        Map<String, Object> result = new HashMap<>();
        try {
            CallQueueDto item = callQueueService.get(callId);
            if (item == null) {
                result.put("success", false);
                result.put("message", "해당 건을 찾을 수 없습니다.");
            } else {
                result.put("success", true);
                result.put("item", item);
            }
        } catch (Exception e) {
            log.error("CALL_QUEUE 조회 실패 call_id={}", callId, e);
            result.put("success", false);
            result.put("message", e.getMessage());
        }
        return result;
    }

    /**
     * PUT /api/queue/{callId}
     * body: { sttReport, sttFeedback, propCd, cmpxCd, category, resolveStatus }
     */
    @PutMapping("/{callId}")
    public Map<String, Object> update(@PathVariable Long callId,
                                      @RequestBody Map<String, Object> body) {
        Map<String, Object> result = new HashMap<>();
        try {
            callQueueService.update(callId, body);
            result.put("success", true);
        } catch (Exception e) {
            log.error("CALL_QUEUE 수정 실패 call_id={}", callId, e);
            result.put("success", false);
            result.put("message", e.getMessage());
        }
        return result;
    }

    /**
     * POST /api/queue/{callId}/approve
     * body(선택): { regId: "사번" }
     * → KOK_CALL_MNTR INSERT + Qdrant 인덱싱 + status=REGISTERED
     */
    @PostMapping("/{callId}/approve")
    public Map<String, Object> approve(@PathVariable Long callId,
                                       @RequestBody(required = false) Map<String, Object> body) {
        Map<String, Object> result = new HashMap<>();
        try {
            String regId = body != null && body.get("regId") != null
                    ? body.get("regId").toString() : null;
            result = callQueueService.approve(callId, regId);
        } catch (IllegalArgumentException | IllegalStateException e) {
            result.put("success", false);
            result.put("message", e.getMessage());
        } catch (Exception e) {
            log.error("CALL_QUEUE 승인 실패 call_id={}", callId, e);
            result.put("success", false);
            result.put("message", e.getMessage());
        }
        return result;
    }

    /** POST /api/queue/{callId}/skip → status=SKIPPED */
    @PostMapping("/{callId}/skip")
    public Map<String, Object> skip(@PathVariable Long callId) {
        Map<String, Object> result = new HashMap<>();
        try {
            callQueueService.skip(callId);
            result.put("success", true);
        } catch (Exception e) {
            log.error("CALL_QUEUE 스킵 실패 call_id={}", callId, e);
            result.put("success", false);
            result.put("message", e.getMessage());
        }
        return result;
    }
}
