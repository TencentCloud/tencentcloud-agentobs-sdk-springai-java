# tencentcloud-agentobs-sdk-springai-java

> **语言 / Language**：[English](README.md) · **中文**

**面向 Spring AI 1.x 的腾讯云 CLS 可观测 SDK。**

把 Spring AI 原生产出的 Micrometer `Observation`（`ChatClient` / `ChatModel` / `Advisor` / `ToolCallback` / `EmbeddingModel` / `VectorStore`）镜像成一棵符合 **CLS GenAI Trace 规范**的 OpenTelemetry span 树，通过 **OTLP/HTTP** 协议上报到腾讯云 CLS。

- ✅ **零字节码增强** — 纯 SDK 库，不依赖 javaagent
- ✅ **一行接入** — `ClsObservability.install().registerOn(observationRegistry)`
- ✅ **自带 OTel SDK 并 shade 隔离** — 可与客户 JVM 中已存在的 ARMS javaagent / 其他 OTel 实现安全共存，互不劫持
- ✅ **绕开 `include-prompt` 开关** — 通过反射从 `ObservationContext` 直接取 prompt / completion / tool arguments，无需开隐私开关
- ✅ **CLS GenAI Trace 规范对齐** — `entry → agent → step → chat / tool` 的 span 树，与 agentscope 版同构

---

## 一、Maven 依赖

```xml
<dependency>
    <groupId>com.tencentcloudapi.observability</groupId>
    <artifactId>tencentcloud-agentobs-sdk-springai-java</artifactId>
    <version>0.0.9</version>
</dependency>
```

**宿主工程里必须已有**（本 SDK 以 `provided` 引入，不会强拉版本）：
- `spring-ai-client-chat` / `spring-ai-model` — Spring AI 1.x
- `micrometer-observation` — Spring Boot 3.x 默认已有

---

## 二、CLS 配置（四项必填）

| 环境变量 | 说明 |
|---|---|
| `CLS_ENDPOINT` | 形如 `https://<region>.cls.tencentcs.com` |
| `CLS_TOPIC_ID` | CLS 日志主题 ID |
| `CLS_SECRET_ID` | 腾讯云访问密钥 ID |
| `CLS_SECRET_KEY` | 腾讯云访问密钥 Key |

可选：`CLS_SERVICE_NAME`、`CLS_SOURCE`、`CLS_BATCH_SIZE`、`CLS_DEBUG`。

**三种配置来源**，优先级从高到低：
1. `ClsConfig.builder().endpoint(...)` 显式传
2. 进程环境变量
3. `./.env` 或 `~/.agentobs-springai/config.env`

---

## 三、接入（Spring Boot 典型姿势）

```java
import com.tencentcloudapi.observability.springai.ClsObservability;
import io.micrometer.observation.ObservationRegistry;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Configuration;

@Configuration
public class ClsObservabilityConfig {

    @Autowired
    private ObservationRegistry observationRegistry;

    @PostConstruct
    public void setup() {
        ClsObservability.install()                 // 读 env / .env / builder
                .registerOn(observationRegistry);  // 挂到 Spring AI 共用的 registry
    }
}
```

> **关键**：必须把 handler 挂到 Spring AI 实际使用的那个 `ObservationRegistry` bean 上（Spring Boot auto-config 默认提供一个全局 bean 名为 `observationRegistry`）。如果你自己 new 了一个新的 Registry 并挂上去，Spring AI 的事件不会流过来。

### 非 Spring Boot 场景

```java
ClsObservability obs = ClsObservability.install();
ObservationRegistry registry = /* 你自己持有的 registry */;
obs.registerOn(registry);
```

---

## 四、SDK 产出的 Span 结构（CLS GenAI Trace 规范）

本 SDK 产出的 span 树**与 agentscope 版同构**，完全符合 CLS GenAI Trace 规范：

```
entry   (SERVER, gen_ai.span.kind=entry)        ← 每次 ChatClient 调用一个
  └─ agent (INTERNAL, gen_ai.span.kind=agent)
       ├─ step (INTERNAL, gen_ai.span.kind=step)  ← 每个 Advisor
       │   ├─ chat (CLIENT, gen_ai.span.kind=chat)   ← ChatModel 调用
       │   └─ tool (CLIENT, gen_ai.span.kind=tool)   ← tool/function 调用
       ├─ step (operation.name=embedding | retrieval)
       ├─ chat (直接从 ChatClient 调模型)
       └─ tool (直接从 ChatClient 调工具)
```

### 每个 span 都会写入的 CLS 规范字段

| 字段 | 说明 |
|---|---|
| `gen_ai.span.kind` | `entry` / `agent` / `step` / `chat` / `tool` |
| `gen_ai.operation.name` | `enter_application` / `invoke_agent` / `react` / `chat` / `execute_tool` / `embedding` / `retrieval` |
| `gen_ai.agent.type` | 固定 `spring-ai` |
| `gen_ai.session.id` | 从 `ClsBaggage.setSession()` 读取，未设时派生 UUID |
| `gen_ai.turn.id` | `{sessionId}:t{N}` |
| `gen_ai.step.id` | `{turnId}:s{N}`（step / chat / tool 自带）|
| `gen_ai.user.id` | 从 `ClsBaggage.setUser()` 读取（可选）|

### 内容字段（通过反射从 `ObservationContext` 直接抓，不依赖 `include-prompt` 开关）

| 字段 | 位置 | 值格式 |
|---|---|---|
| `gen_ai.input.messages` | entry / agent / chat / step（请求侧）| **JSON 数组**，遵循 OpenTelemetry GenAI SemConv v1.37+：`[{"role":"system","content":"..."},{"role":"user","content":"..."}]` |
| `gen_ai.output.messages` | entry / agent / chat / step（响应侧）| **JSON 数组**：`[{"role":"assistant","content":"...","tool_calls":[{"id":"...","type":"function","function":{"name":"...","arguments":"..."}}]}]` |
| `gen_ai.tool.name` / `gen_ai.tool.type` / `gen_ai.tool.call.id` | tool |  |
| `gen_ai.tool.call.arguments` / `gen_ai.tool.call.result` | tool | arguments 为 JSON object 字符串、result 为工具返回值原字符串 |
| `gen_ai.tool.error.type` | tool（异常时）|  |
| `gen_ai.usage.{input_tokens, output_tokens, total_tokens}` | chat（Spring AI 原生）|  |
| `gen_ai.finish_reason` / `gen_ai.turn.completed` | entry / agent（stop 时写入）|  |

`gen_ai.input.messages` / `gen_ai.output.messages` 由 SDK 内置的
`ClsGenAIFormatter` 把 Spring AI 的 `Prompt` / `ChatResponse` 对象转成
OpenAI chat 格式的 JSON，CLS 控制台因此能按角色渲染成气泡，而不是一段裸文本。

### 透传 session.id / user.id（强烈建议）

```java
import com.tencentcloudapi.observability.springai.ClsBaggage;

@PostMapping("/chat")
public String chat(@RequestHeader("X-Session-Id") String sessionId,
                   @RequestHeader("X-User-Id")    String userId,
                   @RequestBody String prompt) {
    ClsBaggage.set(sessionId, userId);
    try {
        return chatClient.prompt().user(prompt).call().content();
    } finally {
        ClsBaggage.clear();
    }
}
```

不透传时，SDK 会自动派生一个 `sess-xxxxxxxxxxxxxxxx` 作为 session.id，trace 依然完整，只是不同用户/会话无法在 CLS 页面上聚合。

---

## 五、本地验证（demo 子工程）

仓库自带一个端到端联调 demo，位于 `springai/demo/`。它用一棵合成的 Spring-AI-shape observation 树**完整覆盖** handler 的所有代码路径（entry / agent / step / chat×2 / tool，共 6 个 span），不依赖真实 LLM endpoint，无网络成本。

### 运行

```bash
# 1) 配置 CLS
export CLS_ENDPOINT=https://ap-guangzhou.cls.tencentcs.com
export CLS_TOPIC_ID=xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx
export CLS_SECRET_ID=AKID...
export CLS_SECRET_KEY=...

# 2) 先把主 SDK 装入本地 Maven 仓库
cd springai
mvn -q -DskipTests -Dgpg.skip=true install

# 3) 构建并运行 demo
cd demo
mvn -q -DskipTests package
java -jar target/springai-demo-shaded.jar
```

### 预期输出

控制台会打印本次 demo 使用的 `sessionId`，形如：

```
================================================================
[demo] done. sessionId = demo-session-1760000000000
       userId    = demo-user
       expected  = 6 spans in CLS:
                   1 × entry   (gen_ai.span.kind=entry)
                   1 × agent   (gen_ai.span.kind=agent)
                   1 × step    (gen_ai.span.kind=step)
                   2 × chat    (gen_ai.span.kind=chat)
                   1 × tool    (gen_ai.span.kind=tool)
       → go to CLS and filter by gen_ai.session.id = demo-session-1760000000000
================================================================
```

### 在 CLS 控制台验证

打开 CLS 控制台 → GenAI Trace → 用 `gen_ai.session.id = <上面打印的值>` 过滤，应看到一条完整 trace 包含 6 个 span，父子关系如下：

| # | span name | `gen_ai.span.kind` | `gen_ai.operation.name` | 父 span | 关键字段 |
|---|---|---|---|---|---|
| 1 | `enter_application` | `entry` | `enter_application` | *(root)* | `session.id`、`turn.id`、`user.id`、`input.messages` |
| 2 | `invoke_agent` | `agent` | `invoke_agent` | #1 entry | 同上 + `output.messages`、`finish_reason`、`turn.completed` |
| 3 | `react` | `step` | `react` | #2 agent | `step.id`（如 `demo-session-xxx:t1:s1`）|
| 4 | `chat` | `chat` | `chat` | #3 step | `request.model=gpt-4o`、`usage.input_tokens=42`、`usage.output_tokens=17`、`finish_reasons=tool_calls` |
| 5 | `execute_tool` | `tool` | `execute_tool` | #3 step | `tool.name=get_weather`、`tool.call.arguments={"city":"Beijing"}`、`tool.call.result={"city":"Beijing","temp":"22C",...}` |
| 6 | `chat` | `chat` | `chat` | #3 step | `usage.input_tokens=60`、`usage.output_tokens=25`、`finish_reasons=stop` |

如果以上 6 个 span 都能在 CLS 页面上看到、且父子关系与上表完全一致，说明 SDK 在你的网络环境下已正确接入。

### 验证时常见问题

| 现象 | 原因 | 解决 |
|---|---|---|
| CLS 没有任何 trace | endpoint / topic / secret 不对 | 开 `CLS_DEBUG=1` 看完整上报 URL + 响应码 |
| 有 trace 但只有 1 个 span | handler 没挂到对应 registry | 确认 `obs.registerOn(registry)` 用的是实际跑 observation 的那个 registry 实例 |
| 字段里没有 `session.id` 列 | 没调 `ClsBaggage.set(...)` | 控制器层加一行 `ClsBaggage.set(sid, uid)` |

---

## 六、真实端到端 demo（`demo-springboot/` 子工程）

除了纯 Mock 的 `demo/`，仓库里还提供一个和它并列的 `demo-springboot/`，跑一个**真实的** Spring Boot 3 Web 应用：接腾讯云混元大模型、注册两个 `@Tool`、带本地向量库 RAG，是最接近线上形态的端到端样例。本 SDK 自动把整条链路采集并上报到 CLS GenAI Trace。

| | `demo/` | `demo-springboot/` |
|---|---|---|
| 形态 | Fat jar CLI | Spring Boot 3 Web（`POST /chat`） |
| LLM | Mock（合成 Observation） | **真实** — 混元 `hy4-preview`（via tokenhub） |
| Embedding | 无 | **真实** — `kinfra-text-embedding-0.6b`（1024 维） |
| Tools | 无 | 2 × `@Tool`（天气 / 当前时间） |
| RAG | 无 | `SimpleVectorStore` + 本地 JSON 持久化 |
| 外网 | 不需要 | 需要（混元 + CLS） |

```bash
# 一键启动：
set -a; source ~/.agentobs-springai/config.env; set +a
mvn -q -DskipTests -Dgpg.skip=true install                    # 安装 SDK
mvn -f demo-springboot/pom.xml spring-boot:run                # 启动 demo

# 触发一次完整 trace：
curl -sS -X POST http://localhost:8080/chat \
  -H 'Content-Type: application/json' \
  -H 'X-Session-Id: demo-weather-001' -H 'X-User-Id: alice' \
  -d '{"q":"北京今天天气怎么样？"}'
```

详细说明（环境变量、三条 curl 验证场景、FAQ）见 [`demo-springboot/README.md`](demo-springboot/README.md)。

---

## 七、与 ARMS / 其他 OTel 实现共存

本 SDK **已把 `io.opentelemetry.*` 整体 relocate 到 `com.tencentcloudapi.observability.shaded.io.opentelemetry.*`**，所以：

- 客户 JVM 里有 ARMS javaagent 时，ARMS 的字节码增强**无法**匹配到我们内部的类 → 不会劫持我们的 Tracer。
- 客户自己注册了 `GlobalOpenTelemetry` 时，我们的 `OpenTelemetrySdk` **不走** `buildAndRegisterGlobal()`，是一个完全私有的实例 → 不互相覆盖。
- 唯一不 shade 的是 `io.micrometer.observation.*` —— 因为必须和 Spring AI 共享同一个 SPI 契约才能收到事件。

---

## 八、调试

打开 `CLS_DEBUG=1` 或 `ClsConfig.builder().debug(true)`，会在 SLF4J debug 级日志里打印每条 observation 的 start/stop 时点，便于确认事件是否流入。

常见排查：
1. **收不到 span**：检查是否把 handler 挂到了 Spring AI 实际用的那个 `ObservationRegistry`（而不是你自己 new 的）。
2. **CLS 页面无 trace**：检查 endpoint / topic / secret 是否正确，debug 日志里会打完整 URL。
3. **prompt 内容为空**：Spring AI 的 `include-prompt` 开关默认关 → 原生 `gen_ai.prompt` 字段确实为空；本 SDK 补的 `gen_ai.input.messages` / `gen_ai.output.messages` 字段会有内容，优先看那个。

---

## 9. 回归测试套件

仓库自带一套覆盖 12 个验证用例的端到端回归脚本
（顶层同步/流式、ReAct round-switch、ChatMemory 多轮、工具异常软吞/硬抛、
3 层嵌套 subagent、10 路并发流式、同线程顺序兄弟 ChatClient、
Reactor 跨线程 subagent），入口在
[`scripts/run-regression.sh`](scripts/run-regression.sh)：

```bash
./scripts/run-regression.sh
```

一条命令完成：安装 SDK、启动 demo-springboot、串行跑 12 个 case、
解析服务日志的 trace 结构、输出每个 case 的 PASS/FAIL 判定表。
完整用例目录、断言规则、失败排查、扩展指引详见
[`docs/testing/regression-playbook.md`](docs/testing/regression-playbook.md)。

---

## License

Apache License 2.0.
