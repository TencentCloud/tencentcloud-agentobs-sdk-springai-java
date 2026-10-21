# tencentcloud-agentobs-sdk-springai-java

> **语言 / Language**：[English](README.md) · **中文**

**面向 Spring AI 的腾讯云 CLS 可观测 SDK。**

把 Spring AI 原生产出的 Micrometer `Observation`（`ChatClient` / `ChatModel` / `Advisor` / `ToolCallback` / `EmbeddingModel` / `VectorStore`）镜像成一棵符合 **CLS GenAI Trace 规范**的 OpenTelemetry span 树，通过标准 **OTLP/HTTP** 协议上报到腾讯云 CLS。

- **零字节码增强** — 纯 SDK 库，不依赖 javaagent
- **一行接入** — `ClsObservability.install().registerOn(observationRegistry)`
- **自带 OTel SDK 并 shade 隔离** — 可与 JVM 中已有的 ARMS javaagent / 其他 OTel 实现安全共存，互不劫持
- **绕开 `include-prompt` 开关** — 直接从 `ObservationContext` 读取 prompt / completion / tool 参数，无需打开 Spring AI 的隐私开关
- **对齐 CLS GenAI Trace 规范** — `entry → agent → react → chat / tool` 的 span 树，与 AgentScope 版同构
- **支持子 Agent、多轮 ReAct 与流式** — tool 内部嵌套的 `ChatClient` 识别为 `invoke_subagent`；每一轮工具调用对应一个 `react round_N`；`.stream()` 跨 Reactor 线程仍保持同一条 trace

---

## 一、兼容性

| 组件 | 支持范围 |
|---|---|
| JDK | 17+ |
| Spring AI | 1.0.x 与 2.0.x（基于 1.0.0 编译；`demo-springboot` 跑在 2.0.1 上） |
| Spring Boot | 3.x / 4.x（`demo-springboot` 跑在 4.1.1 上） |
| Micrometer Observation | 跟随宿主 Spring Boot |

---

## 二、Maven 依赖

```xml
<dependency>
    <groupId>com.tencentcloudapi.observability</groupId>
    <artifactId>tencentcloud-agentobs-sdk-springai-java</artifactId>
    <version>1.0.1</version>
</dependency>
```

**宿主工程需自行提供**（SDK 以 `provided` 引入，不会强拉版本）：

- `spring-ai-client-chat` / `spring-ai-model`
- `micrometer-observation`

---

## 三、配置

### 必填

| 环境变量 | 说明 |
|---|---|
| `CLS_ENDPOINT` | CLS OTLP 接入点，如 `https://ap-guangzhou.cls.tencentcs.com`（未写协议时自动补 `https://`） |
| `CLS_TOPIC_ID` | CLS 日志主题 ID |
| `CLS_SECRET_ID` | 腾讯云 SecretId |
| `CLS_SECRET_KEY` | 腾讯云 SecretKey |

四项任缺其一，`install()` 会直接抛 `IllegalStateException` 并列出缺失项。

### 可选

| 环境变量 | Builder 方法 | 默认值 | 说明 |
|---|---|---|---|
| `CLS_SERVICE_NAME` | `serviceName` | `springai-app` | Resource `service.name` |
| `CLS_SERVICE_VERSION` | `serviceVersion` | — | Resource `service.version` |
| `CLS_SERVICE_INSTANCE_ID` | `serviceInstanceId` | `<hostname>@<pid>` | Resource `service.instance.id` |
| `CLS_DEPLOYMENT_ENVIRONMENT` | `deploymentEnvironment` | — | Resource `deployment.environment.name` |
| `CLS_SOURCE` | `source` | 本机 IP | Resource `host.ip` |
| `CLS_BATCH_SIZE` | `batchSize` | `32`（限制在 1..1000） | `BatchSpanProcessor` 单批最大导出条数 |
| `CLS_SAMPLE_RATIO` | `sampleRatio` | `1.0`（限制在 0.0..1.0） | trace 采样率，见[第六节](#六trace-采样) |
| `CLS_DEBUG` | `debug` | `false` | 以 SLF4J `DEBUG` 级别打印每个 observation 的 start / stop |
| `CLS_SPRINGAI_REACTOR_CONTEXT_PROPAGATION` | `reactorContextPropagation` | `true` | 见[第七节](#七流式--reactor) |
| `CLS_CUSTOM_<KEY>` | `customAttribute(key, value)` | — | 以 `agent.custom.<key>` 写入（key 转小写） |

### 配置来源优先级（从高到低）

1. `ClsConfig.builder()...` 显式传值
2. 进程环境变量
3. `./.env` 与 `~/.agentobs-springai/config.env` 中**最先找到的一个**文件（不会覆盖真实环境变量）

> 凭证请放在环境变量或不入库的 `.env` 文件里（本仓库 `.gitignore` 已忽略 `.env`），切勿在代码里硬编码 `SecretId` / `SecretKey`。

span 上报地址为 `<CLS_ENDPOINT>/v1/traces`，请求头带 `Authorization: Basic base64(SecretId:SecretKey)` 与 `topic_id`。

---

## 四、接入

### Spring Boot

```java
import com.tencentcloudapi.observability.springai.ClsObservability;
import io.micrometer.observation.ObservationRegistry;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class ClsObservabilityConfig {

    @Bean(destroyMethod = "shutdown")   // 容器关闭时 flush 剩余 span
    public ClsObservability clsObservability(ObservationRegistry observationRegistry) {
        ClsObservability obs = ClsObservability.install();   // 读 env / .env
        obs.registerOn(observationRegistry);                 // 挂到 Spring AI 使用的 registry
        return obs;
    }
}
```

显式指定配置：

```java
ClsObservability obs = ClsObservability.install(
        ClsConfig.builder()
                .serviceName("order-assistant")
                .serviceVersion("2.3.0")
                .deploymentEnvironment("prod")
                .customAttribute("project_id", "proj_001")
                .build());   // 凭证仍从 env / .env 读取
```

> **关键**：必须挂到 Spring AI 实际使用的**同一个** `ObservationRegistry` bean 上，自己 new 的 registry 收不到任何 Spring AI 事件。Spring Boot 通常由 Actuator 自动装配提供该 bean；若应用里没有，可像 [`demo-springboot` 的 `ClsObsConfig`](demo-springboot/src/main/java/com/tencentcloudapi/observability/springai/demo/boot/config/ClsObsConfig.java) 一样用 `@ConditionalOnMissingBean` 兜底声明一个。

### 非 Spring Boot

```java
ClsObservability obs = ClsObservability.install();
obs.registerOn(registry);   // 你的 ChatClient / ChatModel 所使用的 registry
// ...
obs.flush();      // 可选，立即 flush
obs.shutdown();   // JVM shutdown hook 也会自动执行
```

### 透传 session / user（强烈建议）

```java
import com.tencentcloudapi.observability.springai.ClsBaggage;

@PostMapping("/chat")
public String chat(@RequestHeader("X-Session-Id") String sessionId,
                   @RequestHeader("X-User-Id")    String userId,
                   @RequestBody String prompt) {
    ClsBaggage.set(sessionId, userId);          // 或 set(sessionId, userId, userName)
    try {
        return chatClient.prompt().user(prompt).call().content();
    } finally {
        ClsBaggage.clear();
    }
}
```

| API | 对应 span 字段 |
|---|---|
| `ClsBaggage.set(sessionId, userId[, userName])` | 一次设置多项 |
| `ClsBaggage.setSession(id)` | `gen_ai.session.id` |
| `ClsBaggage.setUser(id)` | `gen_ai.user.id` |
| `ClsBaggage.setUserName(name)` | `gen_ai.user.name` |

Baggage 在 turn 开始时于调用线程上读取并固化，`.stream()` 场景**无需**在 Reactor 算子间手动透传。不调用 `ClsBaggage` 时，SDK 会自动生成 `sess-<16 位十六进制>` 作为 session.id，trace 依然完整，只是无法在 CLS 控制台按用户 / 会话聚合。

---

## 五、产出的 Span 结构

```
enter_application                     SERVER    span.kind=entry      ← 每次顶层 ChatClient 调用一个
└─ invoke_agent main                  INTERNAL  span.kind=agent      agent.scope=main
   ├─ react round_1                   INTERNAL  span.kind=step       ← 第一个 Advisor（嵌套 Advisor 会被折叠）
   │  ├─ chat <model>                 CLIENT    span.kind=chat       ← ChatModel 调用
   │  ├─ execute_tool <tool>          CLIENT    span.kind=tool       ← ToolCallback
   │  │  └─ invoke_subagent <tool>    INTERNAL  span.kind=agent      agent.scope=subagent ← tool 内嵌套的 ChatClient
   │  │     └─ react round_1 ─ chat / execute_tool ...
   │  ├─ embedding                    CLIENT    span.kind=step       ← EmbeddingModel
   │  └─ retrieval <store>            INTERNAL  span.kind=retriever  ← VectorStore 查询
   └─ react round_2 .. N              INTERNAL  span.kind=step       ← 每多一轮工具调用多一个
      └─ chat <model> / execute_tool <tool> ...
```

- **ReAct 多轮**：某次 `chat` 以 `finish_reason=tool_calls` 结束后，下一个 `chat` / `embedding` 会开启新的兄弟 step `react round_N`。
- **子 Agent**：tool 内部调用的 `ChatClient` 识别为 `invoke_subagent <toolName>`（取不到时为 `subagent-N`），共享外层 `session.id` / `turn.id`，并通过 `gen_ai.subagent.parent_tool_call.id` 关联触发它的 tool。支持任意嵌套深度，内外层 `.call()` / `.stream()` 任意组合。
- **兄弟 ChatClient**：同一请求内先后调用多个顶层 `ChatClient`，各自产生**独立的 trace**。
- 其他 observation 映射为 `step`：`image_generation`、`add_documents` / `delete_documents`（VectorStore 写入），或直接使用 observation 名。

### 标识字段

| 字段 | 取值 |
|---|---|
| `gen_ai.span.kind` | `entry` / `agent` / `step` / `chat` / `tool` / `retriever` |
| `gen_ai.operation.name` | `enter_application` / `invoke_agent` / `invoke_subagent` / `react` / `chat` / `execute_tool` / `embedding` / `retrieval` / ... |
| `gen_ai.agent.type` | 固定 `spring-ai` |
| `gen_ai.agent.name` / `gen_ai.agent.scope` | `main` / `main`，或 `<toolName>` / `subagent` |
| `gen_ai.session.id` | 来自 `ClsBaggage`，未设时为 `sess-<16 位十六进制>` |
| `gen_ai.turn.id` | `{sessionId}:t{N}`，N 按 session 递增 |
| `gen_ai.step.id` / `gen_ai.react.round` | `{turnId}:s{N}` / 轮次号（react 及其子 span） |
| `gen_ai.user.id` / `gen_ai.user.name` | 来自 `ClsBaggage`（可选） |
| `gen_ai.request.streaming` | `.stream()` turn 的 entry / agent / chat span 上为 `true`（`.call()` 不写） |
| `agent.custom.<key>` | 来自 `customAttribute` / `CLS_CUSTOM_*`（写在 react / chat / tool / embedding / retrieval span 上） |

### 内容与指标字段

| Span | 字段 |
|---|---|
| entry / agent / react / chat | `gen_ai.input.messages`、`gen_ai.output.messages` —— OpenAI chat 格式 JSON 数组（OTel GenAI SemConv v1.37+），超过 8000 字符截断 |
| chat | `gen_ai.request.model` / `temperature` / `top_p` / `top_k` / `max_tokens` / `frequency_penalty` / `presence_penalty` / `stop_sequences` / `seed`、`gen_ai.response.finish_reasons`、`gen_ai.usage.*`（Spring AI 原生）、`gen_ai.chat.duration_ms` |
| tool | `gen_ai.tool.name` / `type` / `call.id` / `call.arguments` / `call.result`、`gen_ai.tool.call.duration_ms`、`gen_ai.tool.error.type`（异常时） |
| react | `gen_ai.react.finish_reason` |
| embedding | `gen_ai.embeddings.purpose` / `input` / `input_count` / `vector_count` / `vector_dimensions` |
| retrieval | `gen_ai.retrieval.query_text`、`gen_ai.request.top_k`、`gen_ai.retrieval.documents.count`、`gen_ai.retrieval.duration_ms` |
| invoke_subagent | `gen_ai.subagent.parent_tool_call.id` |

Spring AI 附带的 Micrometer low / high cardinality 键值也会一并写入对应 span。出错时 span 会记录异常并置为 `ERROR`；根 turn 的错误还会传播到 `enter_application`。

### Resource 字段

`service.name` / `service.version` / `service.instance.id`、`deployment.environment.name`、`host.name` / `host.ip`、`process.pid` / `process.runtime.*`、`os.type` / `os.description` / `os.version`、`telemetry.sdk.*`。

---

## 六、Trace 采样

默认全量上报（`CLS_SAMPLE_RATIO=1.0`）。需要降低上报量和 CLS 存储成本时调低采样率：

```bash
export CLS_SAMPLE_RATIO=0.1     # 大约每 10 个 turn 保留 1 个
```

```java
ClsObservability.install(
        ClsConfig.builder()
                .sampleRatio(0.1)
                .build());
```

优先级：builder → `-Dcls.springai.sample-ratio` → `CLS_SAMPLE_RATIO` → `.env` → `1.0`。超出 `[0.0, 1.0]` 的值会被钳位；无法解析的值回退为 `1.0`（丢数据比多报更糟）。

**采样决策按 turn 做一次，整个 turn 要么全留、要么全丢。** 只有 `enter_application` 是 trace 根节点，其余所有 span —— agent、react、chat、tool、embedding、retrieval，以及嵌套的 `invoke_subagent` 子树 —— 都是带显式父节点创建的，会通过 OTel 的 `ParentBased` sampler 继承根节点的决策。因此 CLS 上不会出现结构残缺的 trace。

开启前需注意两点：

- **按 turn 而非按 session 采样。** 每次顶层 `ChatClient` 调用都会生成新的 trace id，因此长对话会在轮次之间出现断档。若按会话完整审计比控量更重要，请保持 `1.0`。
- **省的是上报与存储，不是 CPU。** 被采样丢弃的 turn 仍会走完 handler 的状态机、仍会拼装属性字符串，只是跳过导出。

`CLS_SAMPLE_RATIO=0` 会丢弃全部 trace，并在启动时打印一条告警。

---

## 七、流式 / Reactor

`ChatClient.stream()` 会把 Spring AI 内部的 observation 派发到 Reactor 调度线程。为了让它们留在同一条 trace 中，`install()` 会通过反射调用 `reactor.core.publisher.Hooks.enableAutomaticContextPropagation()`（编译期不依赖 `reactor-core`，classpath 中没有 Reactor 时为空操作）。该 hook 是 JVM 全局且幂等的。

如需关闭（例如你已自行开启），任选其一：

- `ClsConfig.builder().reactorContextPropagation(false)`
- `-Dcls.springai.reactor-context-propagation=false`（优先于环境变量）
- `CLS_SPRINGAI_REACTOR_CONTEXT_PROPAGATION=false`

---

## 八、与 ARMS / 其他 OTel 实现共存

构建时已把 `io.opentelemetry.*`（以及 OkHttp / Okio / Protobuf）整体 relocate 到 `com.tencentcloudapi.observability.shaded.*`：

- ARMS javaagent 按 `io.opentelemetry.*` 匹配做字节码增强，**无法**命中本 SDK 的 Tracer，不会劫持我们的 span。
- 内部 `OpenTelemetrySdk` 使用 `build()` 而非 `buildAndRegisterGlobal()`，不会与宿主争抢 `GlobalOpenTelemetry` 单例。
- 唯一刻意**不** shade 的是 `io.micrometer.observation.*` —— 必须与 Spring AI 共享这套 SPI 才能收到事件。

**已知取舍**：若某个工作线程上同时存在 APM 注入的父上下文和上一次 `.stream()` turn 的残留状态，SDK 会从 `Context.root()` 开启新 turn 以保证 CLS trace 独立，该请求的 APM 关联会因此丢失。详见 [CHANGELOG](CHANGELOG.md) `0.0.12`。

---

## 九、本地验证（`demo/`）

`demo/` 用一棵合成的 Spring-AI 形态 observation 树驱动 handler（不调用真实 LLM，只有上报 CLS 需要网络），产出恰好 **6 个 span**。

```bash
# 1) 配置 CLS（或写入 ~/.agentobs-springai/config.env）
export CLS_ENDPOINT=https://ap-guangzhou.cls.tencentcs.com
export CLS_TOPIC_ID=<your-topic-id>
export CLS_SECRET_ID=<your-secret-id>
export CLS_SECRET_KEY=<your-secret-key>

# 2) 在仓库根目录把 SDK 安装到本地 Maven 仓库
mvn -q -DskipTests -Dgpg.skip=true install

# 3) 构建并运行 demo
cd demo
mvn -q -DskipTests package
java -jar target/springai-demo-shaded.jar
```

控制台会打印本次的 `sessionId`。在 CLS 控制台 → GenAI Trace 中用 `gen_ai.session.id = <打印值>` 过滤：

| # | span name | `gen_ai.span.kind` | 父 span | 关键字段 |
|---|---|---|---|---|
| 1 | `enter_application` | `entry` | *(root)* | `session.id`、`turn.id`、`user.id`、`input.messages` |
| 2 | `invoke_agent main` | `agent` | #1 | `agent.name=main`、`agent.scope=main`、`output.messages` |
| 3 | `react round_1` | `step` | #2 | `step.id=<session>:t1:s1`、`react.round=1` |
| 4 | `chat gpt-4o` | `chat` | #3 | `usage.input_tokens=42`、`usage.output_tokens=17` |
| 5 | `execute_tool get_weather` | `tool` | #3 | `tool.call.arguments={"city":"Beijing"}`、`tool.call.result=...` |
| 6 | `chat gpt-4o` | `chat` | #3 | `usage.input_tokens=60`、`usage.output_tokens=25` |

---

## 十、真实端到端 demo（`demo-springboot/`）

基于 Spring Boot 4 + Spring AI 2.0 的 Web 应用，调用真实的腾讯混元大模型（`hy4-preview`，经 tokenhub），包含两个 `@Tool`、本地向量库 RAG、`ChatMemory`、子 Agent 和流式端点。

```bash
set -a; source ~/.agentobs-springai/config.env; set +a    # HUNYUAN_API_KEY + CLS_*
mvn -q -DskipTests -Dgpg.skip=true install                # 安装 SDK
mvn -f demo-springboot/pom.xml spring-boot:run            # 在 :8080 启动 demo

curl -sS -X POST http://localhost:8080/chat \
  -H 'Content-Type: application/json' \
  -H 'X-Session-Id: demo-weather-001' -H 'X-User-Id: alice' \
  -d '{"q":"北京今天天气怎么样？"}'
```

| 端点 | 场景 |
|---|---|
| `POST /chat` | `.call()` + RAG + tools |
| `POST /chat/stream-plain` · `/chat/stream-rich` | 顶层 `.stream()`，纯 LLM / 带 RAG + tools |
| `POST /chat/memory` | `ChatMemory` 多轮对话 |
| `POST /error-demo/soft` · `/error-demo/hard` | tool 异常被吞 / 被重新抛出 |
| `POST /subagent-demo` · `/stream` · `/stream-deep` | 子 Agent：call+call / stream+call / stream+stream |
| `POST /subagent-deep-demo` | 3 层嵌套子 Agent |
| `POST /multi-agent-demo` | 同一请求内两个兄弟 ChatClient |
| `GET /sequential-stream-demo` | 同一请求内顺序执行 4 次顶层 `.stream()` |

详见 [`demo-springboot/README.md`](demo-springboot/README.md)。

---

## 十一、排查

打开 `CLS_DEBUG=1`（或 `.debug(true)`），并把 `com.tencentcloudapi.observability` logger 调到 `DEBUG`，可看到 `onStart  TURN  session=... turn=... traceId=...` 等日志。

| 现象 | 原因 | 解决 |
|---|---|---|
| `IllegalStateException: CLS configuration incomplete` | 缺少必填项 | 配齐四个 `CLS_*` 变量 |
| 完全收不到 span | handler 挂错了 registry | `registerOn(...)` 传入 Spring AI 实际使用的 registry |
| CLS 上看不到 trace | endpoint / topic / 凭证有误 | 查看启动日志 `installed (service=..., endpoint=..., topic=..., sampleRatio=...)` |
| CLS 上只有部分 turn | 采样已生效 | 查看启动日志里的 `sampleRatio`，设为 `CLS_SAMPLE_RATIO=1.0` 可全量上报 |
| `.stream()` 的 span 被拆成多条 trace | Reactor 上下文传播被关闭或开启失败 | 检查启动日志是否有 `enabled Reactor automatic context propagation` |
| `session.id` 是 `sess-...` | 未设置 `ClsBaggage` | 在调用 `ChatClient` 前调用 `ClsBaggage.set(...)` |
| Spring AI 原生 `gen_ai.prompt` 为空 | `include-prompt` 默认关闭 | 使用 SDK 写入的 `gen_ai.input.messages` / `gen_ai.output.messages` |

---

## 十二、回归测试套件

[`scripts/run-regression.sh`](scripts/run-regression.sh) 基于 `demo-springboot` 跑 12 个用例（13 个子用例，G1–G10）：顶层同步 / 流式、ReAct 轮次切换、ChatMemory、tool 异常软吞 / 硬抛、嵌套与 3 层子 Agent、10 路并发流式、兄弟 ChatClient、Reactor 跨线程子 Agent。

```bash
./scripts/run-regression.sh
```

一条命令完成：安装 SDK、启动 demo、执行全部用例、解析服务日志并输出 PASS / FAIL 表。详见 [`docs/testing/regression-playbook.md`](docs/testing/regression-playbook.md)。

---

## 变更记录

见 [CHANGELOG.md](CHANGELOG.md)。

## License

[Apache License 2.0](LICENSE)。
