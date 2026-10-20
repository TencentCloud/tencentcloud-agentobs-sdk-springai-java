package com.tencentcloudapi.observability.springai.demo.boot.controller;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.tencentcloudapi.observability.springai.ClsBaggage;

/**
 * G11 诊断复现：一个 HTTP 请求内顺序执行 4 次 top-level {@code .stream().blockLast()}，
 * 用于验证 "1-A 懒清理 + parentObs 判别" 的核心假设。
 *
 * <p>所有 stream 都发生在同一个 Tomcat nio-exec-N 工作线程上，第 2/3/4 次预期命中
 * 通道 ④（{@code CURRENT_TURN} ThreadLocal 残留），且 {@code context.getParentObservation()}
 * 应为 null — 这是本次 brainstorming 的关键待验证假设。
 */
@RestController
@RequestMapping("/sequential-stream-demo")
public class SequentialStreamDemoController {

    private final ChatClient chatClient;

    public SequentialStreamDemoController(ChatClient.Builder builder) {
        this.chatClient = builder.clone()
                .defaultSystem("你是一个简洁的 AI 助理。回答一两句话即可。")
                .build();
    }

    @GetMapping
    public Map<String, Object> stream4(
            @RequestHeader(value = "X-Session-Id", required = false) String sessionId,
            @RequestHeader(value = "X-User-Id", required = false) String userId) {

        String resolvedSessionId = (sessionId != null && !sessionId.isEmpty())
                ? sessionId : UUID.randomUUID().toString();
        String resolvedUserId = (userId != null && !userId.isEmpty()) ? userId : "anonymous";

        ClsBaggage.set(resolvedSessionId, resolvedUserId);
        try {
            List<String> prompts = List.of(
                    "请用一句话介绍 Python。",
                    "请用一句话介绍 Java。",
                    "请用一句话介绍 Go。",
                    "请用一句话介绍 Rust。"
            );

            Map<String, Object> resp = new LinkedHashMap<>();
            resp.put("session_id", resolvedSessionId);
            resp.put("count", prompts.size());

            for (int i = 0; i < prompts.size(); i++) {
                StringBuilder sb = new StringBuilder();
                chatClient.prompt().user(prompts.get(i))
                        .stream().content()
                        .doOnNext(sb::append)
                        .blockLast();
                resp.put("answer_" + (i + 1), sb.toString());
            }
            return resp;
        } finally {
            ClsBaggage.clear();
        }
    }
}
