package com.tencentcloudapi.observability.springai.demo.boot.service;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.vectorstore.QuestionAnswerAdvisor;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import com.tencentcloudapi.observability.springai.demo.boot.tools.TimeTools;
import com.tencentcloudapi.observability.springai.demo.boot.tools.WeatherTools;

/**
 * 对话编排服务。
 *
 * <p>构造一个预配好 system prompt、tools 和（可选）RAG advisor 的 {@link ChatClient}；
 * 之后每次 {@link #ask(String)} 调用都会：
 * <ol>
 *     <li>（仅当 {@code demo.rag.enabled=true}）
 *         {@link QuestionAnswerAdvisor} 用用户问题做 query embedding
 *         （embedding span）→ 对 {@link VectorStore} 做 top-K 相似度检索
 *         （retrieval span）→ 把命中片段注入 Prompt。</li>
 *     <li>把 Prompt 发给混元 ChatModel（第 1 次 chat span）。</li>
 *     <li>若模型选择 function calling，由 Spring AI 的 ToolCallingManager
 *         调用对应 {@code @Tool} 方法（tool span）。</li>
 *     <li>把工具结果拼回 messages，再次发给混元 ChatModel（第 2 次 chat span）。</li>
 *     <li>返回最终文本回答。</li>
 * </ol>
 *
 * <p>整条链路由本仓库 CLS SDK 自动采集为一棵完整 span 树。知识库问题会多出
 * {@code retrieval} + {@code embedding} 两个子 span；纯 tool 问题则不会触发 RAG。
 *
 * <p><b>关 RAG 跑法</b>：
 * {@code mvn spring-boot:run -Dspring-boot.run.arguments="--demo.rag.enabled=false"}
 * 这会同时阻止 VectorStore bean 的注册和 QuestionAnswerAdvisor 的挂载，整个
 * trace 树里就不会出现任何 embedding / retrieval span —— 适合验证 SDK 的
 * round 切换在"纯 chat / chat+tool"场景下是否仍然正确工作。
 */
@Service
public class AgentService {

    private final ChatClient chatClient;
    /** 纯 LLM 的 ChatClient（无 RAG、无 tool），只用于 G1-plain 验证。
     *  目的是跑最干净的 top-level .stream() 场景，排除 advisor / tool 的干扰。 */
    private final ChatClient streamPlainClient;

    public AgentService(ChatClient.Builder builder,
                        @Autowired(required = false) VectorStore vectorStore,
                        WeatherTools weatherTools,
                        TimeTools timeTools) {

        ChatClient.Builder b = builder
                .defaultSystem("""
                        你是一个贴心、简洁的 AI 助理。
                        - 当用户的问题需要实时信息（例如天气、当前时间）时，务必通过已注册的工具去获取。
                        - 当用户询问关于腾讯云 CLS、Spring AI、OpenTelemetry 的概念或接入方式时,
                          参考上下文里给出的知识库片段作答（若已启用 RAG），并引用关键细节。
                        - 回答使用中文，语气自然友好，不要机械罗列字段。
                        """)
                .defaultTools(weatherTools, timeTools);

        // RAG 可选挂载：VectorStore bean 在 demo.rag.enabled=false 时不注册，
        // 构造参数为 null → 跳过 QuestionAnswerAdvisor 的 defaultAdvisors 注册。
        if (vectorStore != null) {
            QuestionAnswerAdvisor ragAdvisor = QuestionAnswerAdvisor.builder(vectorStore)
                    .searchRequest(SearchRequest.builder()
                            .topK(3)
                            .similarityThreshold(0.3)
                            .build())
                    .build();
            b = b.defaultAdvisors(ragAdvisor);
        }

        this.chatClient = b.build();

        // 独立构建纯 LLM 的 ChatClient（不 clone 带 tool 的 builder，而是用
        // 底层 builder 单独构造），确保 G1-plain 跑到最干净的 top-level stream。
        this.streamPlainClient = builder.clone()
                .defaultSystem("你是一个简洁的 AI 助理。回答一两句话即可。")
                .build();
    }

    /**
     * 发起一轮对话。
     *
     * @param question 用户问题
     * @return 大模型最终回答（纯文本）
     */
    public String ask(String question) {
        return chatClient.prompt()
                .user(question)
                .call()
                .content();
    }

    /**
     * G1-plain 验证入口：顶层 {@code .stream()}，无 RAG、无 tool、无嵌套。
     * 期望 trace 只有 agent → chat 两层核心 span，且 chat round 为 1。
     *
     * <p>用 {@code blockLast()} 聚合整个 Flux 为单个 String 返回，便于 curl 测试。
     */
    public String askStreamPlain(String question) {
        StringBuilder sb = new StringBuilder();
        streamPlainClient.prompt()
                .user(question)
                .stream()
                .content()
                .doOnNext(sb::append)
                .blockLast();
        return sb.toString();
    }

    /**
     * G1-rich 验证入口：顶层 {@code .stream()} + RAG + tool 的组合。
     * 期望 trace 结构：agent → (embedding + retrieval) → chat round1 →
     * tool → chat round2，且顶层是 stream 模式。
     */
    public String askStreamRich(String question) {
        StringBuilder sb = new StringBuilder();
        chatClient.prompt()
                .user(question)
                .stream()
                .content()
                .doOnNext(sb::append)
                .blockLast();
        return sb.toString();
    }
}
