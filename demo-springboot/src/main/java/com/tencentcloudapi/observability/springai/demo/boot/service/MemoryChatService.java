package com.tencentcloudapi.observability.springai.demo.boot.service;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.stereotype.Service;

/**
 * 带 chat memory 的对话服务（G2 验证场景用）。
 *
 * <p>与 {@link AgentService} 的区别：
 * <ul>
 *   <li>独立 {@link ChatClient} 实例，不复用 AgentService 的那一个 ——
 *       隔离测试场景，确保普通 {@code /chat} 的 trace 不被 memory advisor 污染。</li>
 *   <li>默认挂载 {@link MessageChatMemoryAdvisor}，Spring AI 会在每次 call
 *       之前把当前 conversation 的历史消息注入 Prompt，call 之后再把新一轮
 *       的 user/assistant 消息写回 memory。</li>
 *   <li>不带 tool / RAG：聚焦验证 "跨轮次 memory + 跨轮次 turn.id 自增"，
 *       避免 tool / embedding 干扰 trace 结构判读。</li>
 * </ul>
 *
 * <p>每次 {@link #ask(String, String)} 由调用方传入 conversationId（本 demo
 * 直接用 HTTP {@code X-Session-Id}），作用等同于 Spring AI memory 的会话隔离。
 */
@Service
public class MemoryChatService {

    private final ChatClient chatClient;

    public MemoryChatService(ChatClient.Builder builder, ChatMemory chatMemory) {
        this.chatClient = builder.clone()
                .defaultSystem("""
                        你是一个有记忆的助理。请根据历史对话内容回答用户问题；
                        如果用户在后续轮次中引用之前提到的信息（例如姓名、偏好），
                        请准确使用记忆中的内容，不要编造。
                        """)
                .defaultAdvisors(MessageChatMemoryAdvisor.builder(chatMemory).build())
                .build();
    }

    /**
     * 发起一轮带 memory 的对话。
     *
     * @param conversationId 会话标识 —— 本 demo 直接用 X-Session-Id，天然对齐
     *                       CLS SDK 的 {@code gen_ai.session.id}
     * @param question       用户问题
     * @return LLM 回答
     */
    public String ask(String conversationId, String question) {
        return chatClient.prompt()
                .user(question)
                .advisors(a -> a.param(ChatMemory.CONVERSATION_ID, conversationId))
                .call()
                .content();
    }
}
