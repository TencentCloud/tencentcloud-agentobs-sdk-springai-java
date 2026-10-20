package com.tencentcloudapi.observability.springai.demo.boot.controller;

import java.util.Map;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.tencentcloudapi.observability.springai.ClsBaggage;

/**
 * G4 深层嵌套 subagent 演示端点（直链 3 层）。
 *
 * <p>与 {@link SubAgentDemoController} 的区别：该 controller 只有 2 层
 * （outer → inner），本 controller 加到 3 层（outer → inner → innerInner）。
 * 职责链：
 *
 * <ul>
 *   <li>outer：巡检总指挥，持 tool {@code inspectService}</li>
 *   <li>inner：服务巡检专员，持 tool {@code fetchMetrics}（内层 ChatClient #2）</li>
 *   <li>innerInner：指标分析师，纯 LLM（内层 ChatClient #3）</li>
 * </ul>
 *
 * <p>SDK 应将 inner / innerInner 都识别为 subagent，形成一棵一层比一层嵌套的
 * trace，而非 3 棵独立 trace。验证点：
 *
 * <ul>
 *   <li>只有 1 个 {@code entry} / 1 个 TURN</li>
 *   <li>有 2 个 {@code invoke_subagent} span，{@code parent_tool_call.id}
 *       分别指向 outer 的 tool 调用（call_A）和 inner 的 tool 调用（call_B），
 *       两个 call_A/B 必然不同</li>
 *   <li>整条链路共享同一 {@code session.id} / {@code turn.id}</li>
 *   <li>TurnState 栈 push/pop 完整，服务结束无泄漏</li>
 * </ul>
 *
 * <p>设计关键点：两个 tool 分别放在独立的内部 bean 类上（{@link InspectTool}
 * 和 {@link FetchMetricsTool}），避免 outer 不小心看到 inner 的 tool 而破坏
 * 嵌套结构 —— Spring AI 的 @Tool 扫描只扫 {@code defaultTools(...)} 显式传
 * 入的 bean。
 *
 * <p>调用方式：
 * <pre>
 * curl -X POST http://localhost:8080/subagent-deep-demo \
 *      -H 'Content-Type: application/json' \
 *      -H 'X-Session-Id: g4-deep' -H 'X-User-Id: alice' \
 *      -d '{"q":"请对 payment-service 做一次完整巡检"}'
 * </pre>
 */
@RestController
@RequestMapping("/subagent-deep-demo")
public class SubAgentDeepDemoController {

    private final ChatClient outer;

    public SubAgentDeepDemoController(ChatClient.Builder builder) {
        // ─ Layer 3: innerInner —— 纯 LLM，模拟指标分析师 ─────────────
        ChatClient innerInner = builder.clone()
                .defaultSystem("""
                        你是一个资深的指标分析师。收到服务名后,想象你从监控系统
                        拉取了该服务过去 5 分钟的指标并直接以 JSON 输出,不要
                        markdown 代码块、不要解释文字。字段:
                        - service: 服务名
                        - cpu_pct: 0-100 的数字
                        - mem_pct: 0-100 的数字
                        - error_rate_pct: 0-100 的数字
                        - p99_latency_ms: 整数
                        """)
                .build();

        // ─ Layer 2: inner —— 带 fetchMetrics 工具的服务巡检专员 ────
        ChatClient inner = builder.clone()
                .defaultSystem("""
                        你是服务巡检专员。流程必须严格如下:
                        1. 收到服务名后,必须先调用 fetchMetrics 工具拿到该服务
                           最新的原始指标 JSON。不要凭空编造指标。
                        2. 拿到工具返回的指标后,基于指标输出 JSON 巡检报告,
                           字段:service / metrics(嵌套原始指标) / risk_level
                           (低/中/高) / recommendation(建议一句话)。
                        3. 直接输出 JSON,不要 markdown 代码块。
                        """)
                .defaultTools(new FetchMetricsTool(innerInner))
                .build();

        // ─ Layer 1: outer —— 带 inspectService 工具的巡检总指挥 ─────
        this.outer = builder.clone()
                .defaultSystem("""
                        你是运维总指挥。用户要求巡检某些服务时,必须调用
                        inspectService 工具,把服务名传过去。不要凭空编造报告 ——
                        必须用工具的返回作为唯一数据源,然后以简洁的中文整合输出
                        给用户。
                        """)
                .defaultTools(new InspectTool(inner))
                .build();
    }

    @PostMapping
    public String run(@RequestHeader(value = "X-Session-Id", required = false) String sessionId,
                      @RequestHeader(value = "X-User-Id", required = false) String userId,
                      @RequestBody Map<String, String> body) {
        if (sessionId != null) ClsBaggage.set(sessionId, userId);
        try {
            return outer.prompt()
                    .user(body.getOrDefault("q", "请对 payment-service 做一次完整巡检"))
                    .call()
                    .content();
        } finally {
            ClsBaggage.clear();
        }
    }

    // ──────────────── Tool-bearing beans ────────────────

    /** Outer 层的工具载体:持有 inner ChatClient,工具内部调 inner → 触发 subagent 第 2 层。 */
    static final class InspectTool {
        private final ChatClient inner;
        InspectTool(ChatClient inner) { this.inner = inner; }

        @Tool(description = "巡检指定的后端服务,委派给服务巡检专员子 agent 处理,返回 JSON 巡检报告")
        public String inspectService(String serviceName) {
            return inner.prompt()
                    .user("请为服务 '" + serviceName + "' 生成巡检报告")
                    .call()
                    .content();
        }
    }

    /** Inner 层的工具载体:持有 innerInner ChatClient,工具内部调 innerInner → 触发 subagent 第 3 层。 */
    static final class FetchMetricsTool {
        private final ChatClient innerInner;
        FetchMetricsTool(ChatClient innerInner) { this.innerInner = innerInner; }

        @Tool(description = "从监控系统拉取指定服务最近 5 分钟的原始指标 JSON,委派给指标分析师子 agent 处理")
        public String fetchMetrics(String serviceName) {
            return innerInner.prompt()
                    .user("请拉取服务 '" + serviceName + "' 的最新指标")
                    .call()
                    .content();
        }
    }
}
