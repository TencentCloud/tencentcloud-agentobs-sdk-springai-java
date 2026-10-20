package com.tencentcloudapi.observability.springai.demo.boot.config;

import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.ChatMemoryRepository;
import org.springframework.ai.chat.memory.InMemoryChatMemoryRepository;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Chat memory 配置（G2 验证场景用）。
 *
 * <p>提供一个进程内、线程安全的 {@link ChatMemory} bean，供
 * {@code MemoryChatService} 的 {@code MessageChatMemoryAdvisor} 使用。
 *
 * <p>存储实现：{@link InMemoryChatMemoryRepository}（JVM 内 ConcurrentHashMap；
 * 进程重启清空）。窗口大小 20 条消息 —— 足够两三轮对话携带足量历史，又不会
 * 让 Prompt 爆炸。
 *
 * <p>按 {@code conversationId} 做隔离；本 demo 把 HTTP 的 {@code X-Session-Id}
 * 直接当 {@code conversationId}，天然对齐 CLS SDK 的 {@code gen_ai.session.id}。
 */
@Configuration
public class ChatMemoryConfig {

    @Bean
    public ChatMemoryRepository chatMemoryRepository() {
        return new InMemoryChatMemoryRepository();
    }

    @Bean
    public ChatMemory chatMemory(ChatMemoryRepository repo) {
        return MessageWindowChatMemory.builder()
                .chatMemoryRepository(repo)
                .maxMessages(20)
                .build();
    }
}
