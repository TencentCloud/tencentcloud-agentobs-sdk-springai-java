package com.tencentcloudapi.observability.springai.demo.boot.controller;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.tencentcloudapi.observability.springai.ClsBaggage;
import com.tencentcloudapi.observability.springai.demo.boot.service.AgentService;
import com.tencentcloudapi.observability.springai.demo.boot.service.MemoryChatService;

/**
 * HTTP 入口。
 *
 * <p>关键点：进入业务代码前，把 HTTP 层的 {@code X-Session-Id} / {@code X-User-Id}
 * 通过 {@link ClsBaggage} 塞进 ThreadLocal，让后续 ChatClient 调用产生的
 * trace 都挂上这俩字段（CLS 控制台可以按 session.id 聚合一次对话的全部 trace）。
 *
 * <p>端点清单：
 * <ul>
 *   <li>{@code POST /chat}            — 同步 {@code .call()}（G7 regression、常规业务）</li>
 *   <li>{@code POST /chat/stream-plain} — 顶层 {@code .stream()} + 纯 LLM（G1-plain）</li>
 *   <li>{@code POST /chat/stream-rich}  — 顶层 {@code .stream()} + RAG + tool（G1-rich）</li>
 *   <li>{@code POST /chat/memory}       — 带 memory 的多轮对话（G2）</li>
 * </ul>
 */
@RestController
@RequestMapping("/chat")
public class ChatController {

    private final AgentService agent;
    private final MemoryChatService memoryAgent;

    public ChatController(AgentService agent, MemoryChatService memoryAgent) {
        this.agent = agent;
        this.memoryAgent = memoryAgent;
    }

    @PostMapping
    public Map<String, Object> chat(
            @RequestHeader(value = "X-Session-Id", required = false) String sessionId,
            @RequestHeader(value = "X-User-Id",    required = false) String userId,
            @RequestBody Map<String, String> body) {

        String resolvedSessionId = (sessionId != null && !sessionId.isEmpty())
                ? sessionId : UUID.randomUUID().toString();
        String resolvedUserId = (userId != null && !userId.isEmpty())
                ? userId : "anonymous";

        ClsBaggage.set(resolvedSessionId, resolvedUserId);
        try {
            String question = body.getOrDefault("q", body.getOrDefault("question", ""));
            String answer = agent.ask(question);

            Map<String, Object> resp = new LinkedHashMap<>();
            resp.put("session_id", resolvedSessionId);
            resp.put("user_id", resolvedUserId);
            resp.put("question", question);
            resp.put("answer", answer);
            return resp;
        } finally {
            ClsBaggage.clear();
        }
    }

    /**
     * G1-plain 端点：顶层 {@code .stream()}，纯 LLM，无 tool 无 RAG。
     * 期望 trace：entry → agent → chat（单 round），{@code stream=true} 标记。
     */
    @PostMapping("/stream-plain")
    public Map<String, Object> chatStreamPlain(
            @RequestHeader(value = "X-Session-Id", required = false) String sessionId,
            @RequestHeader(value = "X-User-Id",    required = false) String userId,
            @RequestBody Map<String, String> body) {

        String resolvedSessionId = (sessionId != null && !sessionId.isEmpty())
                ? sessionId : UUID.randomUUID().toString();
        String resolvedUserId = (userId != null && !userId.isEmpty())
                ? userId : "anonymous";

        ClsBaggage.set(resolvedSessionId, resolvedUserId);
        try {
            String question = body.getOrDefault("q", body.getOrDefault("question", ""));
            String answer = agent.askStreamPlain(question);

            Map<String, Object> resp = new LinkedHashMap<>();
            resp.put("session_id", resolvedSessionId);
            resp.put("user_id", resolvedUserId);
            resp.put("mode", "stream-plain");
            resp.put("question", question);
            resp.put("answer", answer);
            return resp;
        } finally {
            ClsBaggage.clear();
        }
    }

    /**
     * G1-rich 端点：顶层 {@code .stream()} + RAG + tool，真实业务组合。
     * 期望 trace：entry → agent → embedding + retrieval → chat round1
     * （含 tool_calls finish_reason）→ tool → chat round2（stop）。
     */
    @PostMapping("/stream-rich")
    public Map<String, Object> chatStreamRich(
            @RequestHeader(value = "X-Session-Id", required = false) String sessionId,
            @RequestHeader(value = "X-User-Id",    required = false) String userId,
            @RequestBody Map<String, String> body) {

        String resolvedSessionId = (sessionId != null && !sessionId.isEmpty())
                ? sessionId : UUID.randomUUID().toString();
        String resolvedUserId = (userId != null && !userId.isEmpty())
                ? userId : "anonymous";

        ClsBaggage.set(resolvedSessionId, resolvedUserId);
        try {
            String question = body.getOrDefault("q", body.getOrDefault("question", ""));
            String answer = agent.askStreamRich(question);

            Map<String, Object> resp = new LinkedHashMap<>();
            resp.put("session_id", resolvedSessionId);
            resp.put("user_id", resolvedUserId);
            resp.put("mode", "stream-rich");
            resp.put("question", question);
            resp.put("answer", answer);
            return resp;
        } finally {
            ClsBaggage.clear();
        }
    }

    /**
     * G2 端点：带 memory 的多轮对话。{@code X-Session-Id} 既是 CLS SDK 的
     * {@code gen_ai.session.id}，也是 memory 的 {@code conversationId}。
     * 同一 session 多次调用，期望 {@code turn.id} 自增（{@code sess:t1, t2, ...}），
     * 且 LLM 能从 memory 读到之前轮次的内容。
     */
    @PostMapping("/memory")
    public Map<String, Object> chatMemory(
            @RequestHeader(value = "X-Session-Id", required = false) String sessionId,
            @RequestHeader(value = "X-User-Id",    required = false) String userId,
            @RequestBody Map<String, String> body) {

        String resolvedSessionId = (sessionId != null && !sessionId.isEmpty())
                ? sessionId : UUID.randomUUID().toString();
        String resolvedUserId = (userId != null && !userId.isEmpty())
                ? userId : "anonymous";

        ClsBaggage.set(resolvedSessionId, resolvedUserId);
        try {
            String question = body.getOrDefault("q", body.getOrDefault("question", ""));
            String answer = memoryAgent.ask(resolvedSessionId, question);

            Map<String, Object> resp = new LinkedHashMap<>();
            resp.put("session_id", resolvedSessionId);
            resp.put("user_id", resolvedUserId);
            resp.put("mode", "memory");
            resp.put("question", question);
            resp.put("answer", answer);
            return resp;
        } finally {
            ClsBaggage.clear();
        }
    }
}
