# 腾讯云 CLS GenAI Trace 简介

腾讯云日志服务 (Cloud Log Service, CLS) 是腾讯云提供的一站式日志服务平台。在 2025 年推出的 **GenAI Trace** 功能，专为大模型应用的全链路可观测性而设计。

## 核心能力

- **GenAI 专用字段**: 支持 `gen_ai.*` 命名空间下的所有 OpenTelemetry 语义约定字段，例如 `gen_ai.input.messages`、`gen_ai.output.messages`、`gen_ai.tool.call.arguments` 等。
- **会话聚合**: 通过 `gen_ai.session.id` 字段可以把一次对话（可能跨越数十次 LLM 调用、工具调用、向量检索）聚合成一棵完整的 trace 树。
- **成本洞察**: 自动汇总 `gen_ai.usage.input_tokens` / `gen_ai.usage.output_tokens`，按模型、按用户维度分析成本。

## 接入方式

### 方式一：使用 CLS 官方 SDK 直接写日志

适合对日志格式有完全自主控制的用户。

### 方式二：使用 OTLP/HTTP 协议上报（推荐）

CLS 的 GenAI Trace 完全兼容 OpenTelemetry Protocol (OTLP) over HTTP。应用只需配置：

- **endpoint**: `https://<region>.cls.tencentcs.com/v1/traces`
- **Authorization**: `Basic base64(SecretId:SecretKey)`
- **topic_id** header: CLS 日志主题 UUID

本仓库的 `tencentcloud-agentobs-sdk-springai-java` SDK 就是走的 OTLP/HTTP 方式。

## 典型 Span 树结构

一次 Spring AI 的 ChatClient 调用在 CLS 上会呈现为以下 span 树：

```
entry (POST /chat)
└── agent (spring-ai)
      ├── step (Advisor)
      │     └── retrieval (vector_store similarity search)
      ├── chat (model round 1, finish_reason=tool_calls)
      ├── tool (get_weather)
      └── chat (model round 2, finish_reason=stop)
```

每个 span 都带有完整的 prompt / completion / tool arguments 内容，方便 debug 与 replay。
