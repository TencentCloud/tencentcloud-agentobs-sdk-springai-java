package com.tencentcloudapi.observability.springai.demo.boot.controller;

import java.util.Map;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.tencentcloudapi.observability.springai.ClsBaggage;

/**
 * 子 agent（nested ChatClient）演示端点。
 *
 * <p>向主 agent 注册一个工具 {@code inspect_service}；工具实现里内部再调用
 * 一个 {@link ChatClient}（内层 agent）。SDK 现在会把内层 ChatClient 识别为
 * CLS GenAI Trace v5 的 <b>subagent</b>，trace 树形态：
 *
 * <pre>
 * entry (enter_application)
 *   └ invoke_agent (scope=main, name=main)
 *       └ react
 *           ├ chat (主 agent LLM)
 *           └ execute_tool "inspect_service"  (tool.call.id=call_xxx)
 *               └ invoke_subagent (scope=subagent, name=inspect_service)
 *                   └ react
 *                       └ chat (子 agent LLM)
 * </pre>
 *
 * <p>只有一个 {@code enter_application}（符合 "1 trace 1 entry" 规范），
 * {@code gen_ai.turn.id} 在整棵树里相同，{@code gen_ai.subagent.parent_tool_call.id}
 * 回指到触发它的 tool call。
 *
 * <p>调用方式：
 * <pre>
 * curl -X POST http://localhost:8080/subagent-demo \
 *      -H 'Content-Type: application/json' \
 *      -H 'X-Session-Id: subagent-test-1' \
 *      -H 'X-User-Id: alice' \
 *      -d '{"q":"请对 payment-service 做一次巡检并输出结构化报告"}'
 * </pre>
 */
@RestController
@RequestMapping("/subagent-demo")
public class SubAgentDemoController {

    private final ChatClient outer;
    private final ChatClient inner;

    public SubAgentDemoController(ChatClient.Builder builder) {
        // 内层 agent：纯 LLM，无 tool
        this.inner = builder.clone()
                .defaultSystem("""
                        你是一个服务巡检专员，任务是分析指定服务的健康状况。
                        收到服务名后，想象你调取了该服务的指标并输出一份简洁的巡检报告：
                        - 关键指标（CPU/内存/错误率）
                        - 风险等级（低/中/高）
                        - 建议
                        用 JSON 格式输出，不要包含 markdown 代码块。
                        """)
                .build();
        // 主 agent：带 inspect_service 工具，工具内部调用内层 agent → 触发 subagent
        this.outer = builder.clone()
                .defaultSystem("""
                        你是一个运维总指挥，用户会要求你巡检某些服务。
                        调用 inspect_service 工具对每个服务做巡检，然后把子 agent 的报告整合输出。
                        不要自己编造巡检结果，必须通过工具获取。
                        """)
                .defaultTools(this)
                .build();
    }

    @PostMapping
    public String run(@RequestHeader(value = "X-Session-Id", required = false) String sessionId,
                      @RequestHeader(value = "X-User-Id", required = false) String userId,
                      @RequestBody Map<String, String> body) {
        if (sessionId != null) ClsBaggage.set(sessionId, userId);
        try {
            return outer.prompt().user(body.getOrDefault("q", "巡检 payment-service")).call().content();
        } finally {
            ClsBaggage.clear();
        }
    }

    /**
     * 响应式版本：outer 用 {@code .stream()} 发起，验证 SDK 的 subagent 嵌套检测
     * 在 Reactor / 跨线程场景下仍然工作（主通道 OTel Context 的能力验证点）。
     *
     * <p>内层仍然用 {@code .call()}：这是 Spring AI tool 回调的最常见形态
     * （tool 实现函数是同步签名，LLM 产物也是同步字符串）。
     *
     * <p>注意：当前实现用 {@code blockLast()} 聚合整个响应流为一个字符串返回，
     * 便于用 curl 一次性看到结果。真实业务场景可以直接返回 {@code Flux<String>}。
     */
    @PostMapping("/stream")
    public String runStream(@RequestHeader(value = "X-Session-Id", required = false) String sessionId,
                            @RequestHeader(value = "X-User-Id", required = false) String userId,
                            @RequestBody Map<String, String> body) {
        if (sessionId != null) ClsBaggage.set(sessionId, userId);
        try {
            StringBuilder sb = new StringBuilder();
            outer.prompt()
                    .user(body.getOrDefault("q", "巡检 payment-service"))
                    .stream()
                    .content()
                    .doOnNext(sb::append)
                    .blockLast();   // 聚合整个 flux，便于 curl 测试
            return sb.toString();
        } finally {
            ClsBaggage.clear();
        }
    }

    /**
     * 双层响应式：outer 用 {@code .stream()}，内层 tool 实现也用 {@code .stream()}
     * （见 {@link #inspectServiceStream}）。这是嵌套检测的最严苛场景 ——
     * outer 和 inner 都会在 Reactor 的 BoundedElastic / parallel 线程上调度，
     * 嵌套 TurnState 的跨线程检测必须完全依赖 Micrometer Observation parent chain。
     *
     * <p>调用方式：
     * <pre>
     * curl -X POST http://localhost:8080/subagent-demo/stream-deep \
     *      -H 'Content-Type: application/json' \
     *      -H 'X-Session-Id: deep-stream-1' \
     *      -d '{"q":"请对 payment-service 做一次巡检"}'
     * </pre>
     */
    @PostMapping("/stream-deep")
    public String runStreamDeep(@RequestHeader(value = "X-Session-Id", required = false) String sessionId,
                                @RequestHeader(value = "X-User-Id", required = false) String userId,
                                @RequestBody Map<String, String> body) {
        if (sessionId != null) ClsBaggage.set(sessionId, userId);
        try {
            StringBuilder sb = new StringBuilder();
            outer.prompt()
                    // 显式让 LLM 调用 stream 版本的 tool
                    .user("使用 inspectServiceStream 工具 — " + body.getOrDefault("q", "巡检 payment-service"))
                    .stream()
                    .content()
                    .doOnNext(sb::append)
                    .blockLast();
            return sb.toString();
        } finally {
            ClsBaggage.clear();
        }
    }

    /**
     * Spring AI 的 {@code @Tool} 回调；由主 agent 的 LLM 决定何时调用。工具实现里
     * 发起内层 ChatClient 调用 —— 这会触发 SDK 的 subagent 检测。
     */
    @Tool(description = "巡检指定的后端服务，返回结构化报告 JSON")
    public String inspectService(String serviceName) {
        return inner.prompt()
                .user("请为服务 '" + serviceName + "' 生成巡检报告")
                .call()
                .content();
    }

    /**
     * {@link #inspectService} 的响应式双胞胎：工具实现用 inner.{@code .stream()}
     * 发起内层调用并用 {@code blockLast()} 聚合，用于验证 outer+inner 双层
     * 响应式场景下 SDK 的 subagent 嵌套检测仍然工作。
     *
     * <p>Spring AI 的 {@code @Tool} 签名必须返回 String（同步），所以这里用
     * {@code blockLast()} 在 tool 线程里等待流完成 —— 这不影响内层 ChatClient
     * 本身走的 {@code .stream()} 路径（观测点在 inner 的 observation onStart
     * 处，那时 Reactor 调度已经发生）。
     */
    @Tool(description = "以流式方式巡检指定的后端服务，返回结构化报告 JSON")
    public String inspectServiceStream(String serviceName) {
        StringBuilder sb = new StringBuilder();
        inner.prompt()
                .user("请为服务 '" + serviceName + "' 生成巡检报告")
                .stream()
                .content()
                .doOnNext(sb::append)
                .blockLast();
        return sb.toString();
    }
}
