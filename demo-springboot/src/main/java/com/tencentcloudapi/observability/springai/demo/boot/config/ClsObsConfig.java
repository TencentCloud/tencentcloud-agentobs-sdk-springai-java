package com.tencentcloudapi.observability.springai.demo.boot.config;

import com.tencentcloudapi.observability.springai.ClsConfig;
import com.tencentcloudapi.observability.springai.ClsObservability;

import io.micrometer.observation.ObservationRegistry;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * CLS 可观测性接入配置。
 *
 * <p>把本仓库 SDK 的 {@link ClsObservability} 注册到 Spring Boot 自动创建的
 * {@link ObservationRegistry} Bean 上，之后 Spring AI 的所有调用（ChatClient /
 * ChatModel / Advisor / ToolCallback / EmbeddingModel / VectorStore）产生的
 * Micrometer Observation 都会被 SDK 自动镜像为 OpenTelemetry Span 并通过
 * OTLP/HTTP 上报到腾讯云 CLS GenAI Trace。
 *
 * <p>CLS 鉴权凭证来自环境变量或 {@code ~/.agentobs-springai/config.env}：
 * <pre>
 *   CLS_ENDPOINT   = https://ap-&lt;region&gt;.cls.tencentcs.com
 *   CLS_TOPIC_ID   = &lt;日志主题 UUID&gt;
 *   CLS_SECRET_ID  = &lt;腾讯云 SecretId&gt;
 *   CLS_SECRET_KEY = &lt;腾讯云 SecretKey&gt;
 * </pre>
 *
 * <p>{@code destroyMethod = "shutdown"} 让 Spring 容器关闭时自动 flush 剩余 span，
 * 避免丢数据。
 */
@Configuration
public class ClsObsConfig {

    /**
     * 兜底提供一个 {@link ObservationRegistry}。
     *
     * <p>Spring Boot 3 的 {@code ObservationRegistry} Bean 通常由
     * {@code spring-boot-actuator} 的自动装配提供；本 demo 为保持轻量没有引入
     * actuator，因此这里显式提供一个。一旦将来加了 actuator，Spring 容器里会
     * 已经存在一个 {@code ObservationRegistry}，这个 Bean 会被
     * {@link ConditionalOnMissingBean} 跳过。
     */
    @Bean
    @ConditionalOnMissingBean
    public ObservationRegistry observationRegistry() {
        return ObservationRegistry.create();
    }

    @Bean(destroyMethod = "shutdown")
    public ClsObservability clsObservability(ObservationRegistry observationRegistry) {
        ClsObservability obs = ClsObservability.install(
                ClsConfig.builder()
                        .serviceName("springai-hunyuan-demo")
                        .debug(true) // 本地演示打开，上生产请关掉
                        .build());

        // 关键：挂到 Spring Boot 已经注入给 Spring AI 的那个 ObservationRegistry 上。
        // 不要新建 ObservationRegistry.create()，否则 Spring AI 的事件进不来。
        obs.registerOn(observationRegistry);

        return obs;
    }
}
