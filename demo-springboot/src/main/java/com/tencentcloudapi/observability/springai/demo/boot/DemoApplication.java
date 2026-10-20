package com.tencentcloudapi.observability.springai.demo.boot;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 真实 Spring Boot 3 + Spring AI 1.0 + 腾讯云混元 OpenAI 兼容接口 + 本地 RAG
 * 端到端 Demo 启动类。
 *
 * <p>启动后暴露 {@code POST /chat} 接口，接收用户问题并返回大模型回答；
 * 整条链路（HTTP → Advisor → RAG retrieval → ChatModel → Tool → ChatModel）
 * 由本仓库 CLS SDK 自动采集并上报至腾讯云 CLS GenAI Trace。
 *
 * <p>必需环境变量：
 * <pre>
 *   HUNYUAN_API_KEY   — 混元 OpenAI 兼容接口 API Key（sk-...）
 *   CLS_ENDPOINT      — 如 https://ap-guangzhou.cls.tencentcs.com
 *   CLS_TOPIC_ID      — CLS 日志主题 UUID
 *   CLS_SECRET_ID     — 腾讯云 SecretId
 *   CLS_SECRET_KEY    — 腾讯云 SecretKey
 * </pre>
 *
 * <p>启动：
 * <pre>
 *   mvn -f demo-springboot/pom.xml spring-boot:run
 * </pre>
 */
@SpringBootApplication
public class DemoApplication {

    public static void main(String[] args) {
        SpringApplication.run(DemoApplication.class, args);
    }
}
