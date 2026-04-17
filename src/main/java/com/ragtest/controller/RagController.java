package com.ragtest.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ragtest.service.RagService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import javax.servlet.http.HttpServletRequest;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/rag")
public class RagController {

    @Autowired
    private RagService ragService;

    // MariaDB 데이터 전체 인덱싱 (최초 1회)
    @PostMapping("/index")
    public Map<String, Object> index() {
        Map<String, Object> result = new HashMap<>();
        try {
            String msg = ragService.indexAll();
            result.put("success", true);
            result.put("message", msg);
        } catch (Exception e) {
            result.put("success", false);
            result.put("message", e.getMessage());
        }
        return result;
    }

    // 질문 → RAG 답변
    @PostMapping("/ask")
    public Map<String, Object> ask(HttpServletRequest request) {
        Map<String, Object> result = new HashMap<>();
        try {
            StringBuilder sb = new StringBuilder();
            BufferedReader reader = new BufferedReader(
                    new InputStreamReader(request.getInputStream(), "UTF-8")
            );
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line);
            }

            ObjectMapper mapper = new ObjectMapper();
            Map<String, String> body = mapper.readValue(sb.toString(), Map.class);
            String question = body.get("question");

            System.out.println("받은 질문: " + question);

            String answer = ragService.ask(question);
            result.put("success", true);
            result.put("answer", answer);
        } catch (Exception e) {
            result.put("success", false);
            result.put("message", e.getMessage());
        }
        return result;
    }

    @PostMapping("/index/updated")
    public Map<String, Object> indexUpdated() {
        Map<String, Object> result = new HashMap<>();
        try {
            String msg = ragService.indexUpdated();
            result.put("success", true);
            result.put("message", msg);
        } catch (Exception e) {
            result.put("success", false);
            result.put("message", e.getMessage());
        }
        return result;
    }
}
