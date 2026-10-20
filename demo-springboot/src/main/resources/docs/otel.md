# OpenTelemetry 基础术语

OpenTelemetry (OTel) 是 CNCF 下的开源可观测性框架，统一了 Trace、Metrics、Logs 三大信号的数据模型与采集协议。

## 核心概念

### Trace & Span

- **Trace**: 代表一次完整的请求链路，由多个 Span 组成一棵树。
- **Span**: Trace 的最小单元，表示一次具体的操作（例如一次 HTTP 调用、一次数据库查询、一次 LLM 推理）。每个 Span 有自己的 `trace_id`、`span_id`、`parent_span_id`。
- **Span Kind**: 常见有 `SERVER`、`CLIENT`、`INTERNAL`、`PRODUCER`、`CONSUMER`。

### Attribute

Span 上附加的键值对，用于描述该次操作的具体内容。CLS GenAI Trace 规范定义了 `gen_ai.*` 命名空间：

- `gen_ai.operation.name` — 操作类型（chat / embedding / tool_call ...）
- `gen_ai.request.model` — 请求的模型名
- `gen_ai.usage.input_tokens` / `output_tokens` / `total_tokens`
- `gen_ai.input.messages` / `gen_ai.output.messages` — 完整 prompt 和 completion
- `gen_ai.tool.call.arguments` / `gen_ai.tool.call.result` — 工具调用参数和结果
- `gen_ai.session.id` / `gen_ai.user.id` / `gen_ai.turn.id` / `gen_ai.step.id` — 层级标识符

### Context Propagation

跨进程 / 跨线程时携带 trace 上下文的机制。常见格式：W3C Trace Context (`traceparent` HTTP header)、Baggage。

## OTLP 协议

OpenTelemetry Protocol，支持 gRPC 和 HTTP/Protobuf 两种传输。CLS 的 GenAI Trace 接入点使用 OTLP/HTTP：

```
POST https://<region>.cls.tencentcs.com/v1/traces
Content-Type: application/x-protobuf
Authorization: Basic <base64(secretId:secretKey)>
topic_id: <cls-log-topic-uuid>
```

## 本 SDK 的架构选择

- SDK 内部用 shaded 后的 OpenTelemetry SDK（relocate 到 `com.tencentcloudapi.observability.shaded.io.opentelemetry.*`），避免与宿主应用自己使用的 OTel 或 Alibaba ARMS javaagent 冲突。
- 不注册为 `GlobalOpenTelemetry`，而是作为私有 TracerProvider 存在。
- SpanProcessor 使用 `BatchSpanProcessor`，默认 batch=32，JVM shutdown hook 自动 flush。
