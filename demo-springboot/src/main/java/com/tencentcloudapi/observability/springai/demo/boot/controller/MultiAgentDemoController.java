package com.tencentcloudapi.observability.springai.demo.boot.controller;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.tencentcloudapi.observability.springai.ClsBaggage;

/**
 * G6 多独立 ChatClient 顺序调用演示端点。
 *
 * <p>同一个 HTTP request 里顺序调用两个独立的 ChatClient（都不嵌套,都
 * 顶层调），验证 SDK 的 turn 边界：
 *
 * <ul>
 *   <li>两次调用 → 两个 TURN，{@code turn.id} 应该是 {@code session:t1} 和
 *       {@code session:t2}，不能复用 t1（否则就是 ThreadLocal 清理 bug）</li>
 *   <li>两个 agent span 的 {@code gen_ai.agent.name} 应该不同（分别对应
 *       translator 和 summarizer 两个 ChatClient 实例）</li>
 *   <li>两次都挂在同一 {@code session.id} 下（HTTP header 决定）</li>
 *   <li>Per-session turn counter 跨 ChatClient 实例也要正确自增 ——
 *       即 counter 挂在 session.id 上,不是挂在 ChatClient 实例上</li>
 * </ul>
 *
 * <p>与 G2（多轮 memory）的区别：G2 跨 HTTP request 自增，这里是**同一个
 * request 内**自增，验证的是 TurnState 在 ChatClient #1 调用结束时正确
 * 清理 ThreadLocal。
 *
 * <p>调用方式：
 * <pre>
 * curl -X POST http://localhost:8080/multi-agent-demo \
 *      -H 'Content-Type: application/json' \
 *      -H 'X-Session-Id: g6-multi' -H 'X-User-Id: alice' \
 *      -d '{"q":"今天天气真不错,我想去公园散步"}'
 * </pre>
 */
@RestController
@RequestMapping("/multi-agent-demo")
public class MultiAgentDemoController {

    private final ChatClient translator;
    private final ChatClient summarizer;

    public MultiAgentDemoController(ChatClient.Builder builder) {
        this.translator = builder.clone()
                .defaultSystem("""
                        你是中英翻译助手。收到中文后,直接输出对应的英文翻译,
                        不要加任何多余解释、不要加引号、不要加 markdown。
                        """)
                .build();
        this.summarizer = builder.clone()
                .defaultSystem("""
                        你是英文摘要助手。收到英文后,用一句简短的中文概括其核心意思,
                        不要加多余解释、不要加 markdown。
                        """)
                .build();
    }

    @PostMapping
    public Map<String, Object> run(
            @RequestHeader(value = "X-Session-Id", required = false) String sessionId,
            @RequestHeader(value = "X-User-Id",    required = false) String userId,
            @RequestBody Map<String, String> body) {

        String resolvedSessionId = (sessionId != null && !sessionId.isEmpty())
                ? sessionId : UUID.randomUUID().toString();
        String resolvedUserId = (userId != null && !userId.isEmpty())
                ? userId : "anonymous";

        ClsBaggage.set(resolvedSessionId, resolvedUserId);
        try {
            String input = body.getOrDefault("q", body.getOrDefault("question", ""));

            // Call #1: 顶层 ChatClient (translator) — 期望 turn.id = session:t1
            String english = translator.prompt().user(input).call().content();

            // Call #2: 顶层 ChatClient (summarizer) — 期望 turn.id = session:t2
            // 关键:这里不是嵌套 (translator 已经 .call() 结束,ThreadLocal 已清理),
            // 而是同一 request 的下一步顶层调用,应该开启全新的 TURN。
            String summary = summarizer.prompt().user(english).call().content();

            Map<String, Object> resp = new LinkedHashMap<>();
            resp.put("session_id", resolvedSessionId);
            resp.put("user_id", resolvedUserId);
            resp.put("input", input);
            resp.put("english", english);
            resp.put("summary", summary);
            return resp;
        } finally {
            ClsBaggage.clear();
        }
    }
}
