package com.tencentcloudapi.observability.springai.demo.boot.controller;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.execution.DefaultToolExecutionExceptionProcessor;
import org.springframework.ai.tool.execution.ToolExecutionException;
import org.springframework.ai.tool.execution.ToolExecutionExceptionProcessor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.tencentcloudapi.observability.springai.ClsBaggage;

/**
 * G3 错误路径验证入口。
 *
 * <p>Spring AI 2.0 的 tool 异常默认由 {@link DefaultToolExecutionExceptionProcessor}
 * 吞成文本返给 LLM（继续走第 2 轮 chat），SDK onError 的"仅 tool span ERROR"
 * 分支由此触发；若希望异常冒泡让 chat/agent/entry 也 ERROR，需要自定义
 * processor 不吞异常直接 rethrow。本 demo 两种语义都测：
 *
 * <ul>
 *   <li>{@code POST /error-demo/soft}: tool 抛 {@link SoftFailureException}
 *       → 被 {@link SelectiveRethrowProcessor} 转为文本 → LLM 第 2 轮回答
 *       期望 trace：tool span ERROR（含 {@code tool.error.type=execution_error}），
 *       其余 chat/agent/entry 均 OK。</li>
 *   <li>{@code POST /error-demo/hard}: tool 抛 {@link HardFailureException}
 *       → 被 {@link SelectiveRethrowProcessor} 直接 rethrow →
 *       期望 trace：tool span → agent span → entry span 全部 ERROR。</li>
 * </ul>
 */
@RestController
@RequestMapping("/error-demo")
public class ErrorToolsDemoController {

    private final ChatClient chatClient;

    public ErrorToolsDemoController(ChatClient.Builder builder) {
        // Tools 直接 register this —— @Tool 方法就在 Controller 本身上，
        // Spring AI 的 MethodToolCallbackProvider 会扫描所有 @Tool 注解方法。
        this.chatClient = builder.clone()
                .defaultSystem("""
                        你是一个专门用于故障注入演练的助理。无论用户发什么问题,
                        你都必须直接调用对应的失败模拟工具去触发一次异常,然后根据
                        工具返回或抛出的内容向用户简要说明情况(一两句话即可)。
                        - 当用户走 /soft 路径,调用 simulateSoftFailure
                        - 当用户走 /hard 路径,调用 simulateHardFailure
                        - 工具的 reason 参数直接传用户的问题文本
                        """)
                .defaultTools(this)
                .build();
    }

    // ──────────────── Tools ────────────────

    /** Soft 失败：抛 {@link SoftFailureException}，被默认 processor 吞成文本给 LLM。 */
    @Tool(description = "模拟一次软失败——会抛异常,但异常被 Spring AI 转为文本返回给大模型继续对话")
    public String simulateSoftFailure(String reason) {
        throw new SoftFailureException("soft-failure: " + reason);
    }

    /** Hard 失败：抛 {@link HardFailureException}，被自定义 processor rethrow，整条 trace ERROR。 */
    @Tool(description = "模拟一次硬失败——异常会直接冒泡终止本轮对话")
    public String simulateHardFailure(String reason) {
        throw new HardFailureException("hard-failure: " + reason);
    }

    // ──────────────── Endpoints ────────────────

    @PostMapping("/soft")
    public Map<String, Object> soft(
            @RequestHeader(value = "X-Session-Id", required = false) String sessionId,
            @RequestHeader(value = "X-User-Id",    required = false) String userId,
            @RequestBody Map<String, String> body) {
        return invoke(sessionId, userId, body, "soft");
    }

    @PostMapping("/hard")
    public Map<String, Object> hard(
            @RequestHeader(value = "X-Session-Id", required = false) String sessionId,
            @RequestHeader(value = "X-User-Id",    required = false) String userId,
            @RequestBody Map<String, String> body) {
        return invoke(sessionId, userId, body, "hard");
    }

    private Map<String, Object> invoke(String sessionId, String userId,
                                       Map<String, String> body, String mode) {
        String resolvedSessionId = (sessionId != null && !sessionId.isEmpty())
                ? sessionId : UUID.randomUUID().toString();
        String resolvedUserId = (userId != null && !userId.isEmpty())
                ? userId : "anonymous";

        ClsBaggage.set(resolvedSessionId, resolvedUserId);
        try {
            String question = body.getOrDefault("q", body.getOrDefault("question", ""));
            // Hint which tool to pick via system-prompt convention above.
            String userMsg = "请走 /" + mode + " 路径。用户问题：" + question;

            Map<String, Object> resp = new LinkedHashMap<>();
            resp.put("session_id", resolvedSessionId);
            resp.put("user_id", resolvedUserId);
            resp.put("mode", "error-" + mode);
            resp.put("question", question);
            try {
                String answer = chatClient.prompt().user(userMsg).call().content();
                resp.put("answer", answer);
                resp.put("outcome", "ok");
            } catch (Throwable t) {
                // Hard 场景:异常冒泡出 chatClient.call() 到这里。记录给 curl 看。
                resp.put("outcome", "exception");
                resp.put("exception_class", t.getClass().getName());
                resp.put("exception_message", String.valueOf(t.getMessage()));
            }
            return resp;
        } finally {
            ClsBaggage.clear();
        }
    }

    // ──────────────── Supporting beans / types ────────────────

    /** Marker exception for soft failures: 被默认 processor 吞成文本给 LLM。 */
    public static final class SoftFailureException extends RuntimeException {
        public SoftFailureException(String msg) { super(msg); }
    }

    /** Marker exception for hard failures: 被自定义 processor rethrow，终止调用链。 */
    public static final class HardFailureException extends RuntimeException {
        public HardFailureException(String msg) { super(msg); }
    }

    /**
     * 覆盖 Spring AI 默认的 {@link DefaultToolExecutionExceptionProcessor}：
     * 对 {@link HardFailureException} 直接 rethrow，让 Spring AI 的调用链
     * 把异常冒泡出去（这样 chat / agent / entry span 都会被 SDK 的 onError
     * 分支标记为 ERROR）；其他异常走默认处理（吞成文本给 LLM 继续对话）。
     *
     * <p>声明为 {@link Primary} 以覆盖 autoconfig 的 {@code @ConditionalOnMissingBean}
     * 默认 processor —— 其他 Controller 的 tool（weather / time / inspectService /
     * memory）由于不抛 {@code HardFailureException}，照常走默认吞异常的行为，
     * 彼此不干扰。
     */
    @Configuration
    static class ErrorToolsConfig {
        @Bean
        @Primary
        public ToolExecutionExceptionProcessor selectiveRethrowProcessor() {
            ToolExecutionExceptionProcessor fallback =
                    DefaultToolExecutionExceptionProcessor.builder().alwaysThrow(false).build();
            return new SelectiveRethrowProcessor(fallback);
        }
    }

    /** 复合 processor：{@code HardFailureException} rethrow，其余委托给 default。 */
    static final class SelectiveRethrowProcessor implements ToolExecutionExceptionProcessor {
        private final ToolExecutionExceptionProcessor fallback;
        SelectiveRethrowProcessor(ToolExecutionExceptionProcessor fallback) {
            this.fallback = fallback;
        }
        @Override
        public String process(ToolExecutionException ex) {
            Throwable cause = ex.getCause();
            if (cause instanceof HardFailureException) {
                throw ex;    // rethrow原始 ToolExecutionException,让 Spring AI 冒泡
            }
            return fallback.process(ex);
        }
    }
}
