# demo-springboot — 真实端到端 Spring Boot 4 + Spring AI 2.0 Demo

> 本模块是和 `demo/`（纯 Mock、无网络）并列的**另一种 demo**：它跑真实的
> Spring Boot 4.1 Web 应用（Spring AI 2.0.1），接真实的腾讯云混元大模型，带两个
> 真实的 `@Tool` 函数和基于本地向量库的真实 RAG，并覆盖 ChatMemory、子 Agent、
> 流式、tool 异常等场景，让本仓库 CLS SDK 在**最接近线上形态**的环境下完整采集
> 并上报 trace 到腾讯云 CLS GenAI Trace。

| 对比 | `demo/` | **`demo-springboot/`（本模块）** |
|---|---|---|
| 形态 | 单 jar CLI | Spring Boot 4 Web 应用（多个 HTTP 端点，见第三节） |
| LLM | 纯 Mock（合成 Observation） | **真实调用腾讯云混元**（`hy4-preview` via tokenhub） |
| Embedding | 无 | **真实调用**（`kinfra-text-embedding-0.6b`，1024 维） |
| Tool | 无 | 2 个 `@Tool`：天气 / 当前时间 |
| RAG | 无 | `SimpleVectorStore` + 本地 JSON 持久化 + 3 篇 ingest 文档 |
| 外网 | 不需要 | 需要（混元 + CLS） |
| 用途 | 验证 SDK 全部代码路径不依赖网络 | 让用户看到**真实生产姿势** |

---

## 一、准备工作

### 1.1 环境变量（共 5 个）

| 变量 | 说明 | 示例 |
|---|---|---|
| `HUNYUAN_API_KEY` | tokenhub.tencentmaas.com 的 API Key | `sk-xxxxxxxxxxxx` |
| `CLS_ENDPOINT` | CLS OTLP/HTTP 接入点 | `https://ap-guangzhou.cls.tencentcs.com` |
| `CLS_TOPIC_ID` | CLS GenAI Trace 日志主题 UUID | `xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx` |
| `CLS_SECRET_ID` | 腾讯云 SecretId | `AKID...` |
| `CLS_SECRET_KEY` | 腾讯云 SecretKey | `...` |

> 支持三种填入方式（优先级从高到低）：
> 1. 真实环境变量（`export ...`）
> 2. 项目根 `.env` 文件（已被 `.gitignore` 忽略）
> 3. `~/.agentobs-springai/config.env`
>
> CLS 的 4 个变量由本仓库 SDK 自动从以上来源读取；`HUNYUAN_API_KEY` 则由
> Spring Boot 通过 `application.yml` 里的 `${HUNYUAN_API_KEY:}` 占位符读取。
> 为了一处配置、两边生效，推荐把 5 个变量都写进
> `~/.agentobs-springai/config.env`，启动前 `set -a; source ...; set +a` 一下即可。

### 1.2 安装本仓库 SDK 到本地仓库

```bash
# 在仓库根目录
mvn -q -DskipTests -Dgpg.skip=true -Dmaven.javadoc.skip=true install
```

---

## 二、启动

### 方式 A：Maven 直接跑（最简单）

```bash
set -a; source ~/.agentobs-springai/config.env; set +a
mvn -f demo-springboot/pom.xml spring-boot:run
```

### 方式 B：打包成 fat jar 后跑

```bash
mvn -f demo-springboot/pom.xml -DskipTests package
java -jar demo-springboot/target/springai-hunyuan-demo-*.jar
```

### 方式 C：IDE

直接运行 `DemoApplication.main()`，并在 IDE 的 Run Configuration 里把 5 个
环境变量填上。

### 启动日志关键信号

```
[rag] no persisted vector store at .../vector-store.json — ingesting docs now…
[rag] prepared 3 chunks from classpath:/docs/*.md
[rag] embedded batch 0..2 (3 chunks)
[rag] persisted 3 chunks to .../vector-store.json
CLS observability (Spring AI, OTLP/HTTP) installed (...)
CLS ObservationHandler registered onto ObservationRegistry@...
Tomcat started on port 8080
Started DemoApplication in 0.x seconds
```

**首次启动**会花 1~2 秒调一次 embedding；**第二次启动**直接 `[rag] loaded persisted
vector store` 加载本地 `vector-store.json`，不再付费。

---

## 三、验证（三条 curl 覆盖三种场景）

### 场景 1：纯 Tool Calling（不触发 RAG）

```bash
curl -sS -X POST http://localhost:8080/chat \
  -H 'Content-Type: application/json' \
  -H 'X-Session-Id: demo-weather-001' \
  -H 'X-User-Id: alice' \
  -d '{"q":"北京今天天气怎么样？"}'
```

**预期回答**（示例）：

```json
{"answer":"北京今天是晴天，气温 18°C，东北风 2 级，空气质量优。挺适合出门走走的～"}
```

**预期 CLS trace 树**（示意，在控制台搜 `gen_ai.session.id = demo-weather-001`）：

```
enter_application
└── invoke_agent main
    ├── react round_1                    ← QuestionAnswerAdvisor
    │   ├── retrieval <store>            ← RAG 检索（含 query embedding），召回内容与天气无关
    │   ├── chat hy4-preview             finish_reasons=["tool_calls"]
    │   └── execute_tool getWeather      arguments={"city":"北京"}
    └── react round_2                    ← 上一轮以 tool_calls 结束，SDK 开启新一轮
        └── chat hy4-preview             finish_reasons=["stop"]
```

### 场景 2：纯 RAG 查询（不触发 Tool）

```bash
curl -sS -X POST http://localhost:8080/chat \
  -H 'Content-Type: application/json' \
  -H 'X-Session-Id: demo-rag-001' \
  -H 'X-User-Id: bob' \
  -d '{"q":"腾讯云 CLS 的 GenAI Trace 怎么接入？"}'
```

**预期**：模型会从 `src/main/resources/docs/cls.md` 和 `otel.md` 的向量化片段
里召回相关内容，回答里会出现 `OTLP/HTTP`、`endpoint`、`topic_id`、
`Shaded OTel`、`BatchSpanProcessor` 等词 —— 这些是知识库里的，而不是模型凭空
生成的。

### 场景 3：Tool + RAG 混合

```bash
curl -sS -X POST http://localhost:8080/chat \
  -H 'Content-Type: application/json' \
  -H 'X-Session-Id: demo-mix-001' \
  -d '{"q":"先告诉我上海现在几点，再给我讲讲 Spring AI 的 ChatClient 是什么"}'
```

**预期**：先调 `getCurrentTime("Asia/Shanghai")`，再引用知识库里 Spring AI 的
介绍回答。CLS 上会看到 chat → tool → chat → 包含 RAG 召回的最终 chat。

### 其他端点（回归套件 G1–G11 使用）

请求体统一为 `{"q":"..."}`，均支持 `X-Session-Id` / `X-User-Id` 请求头。

| 端点 | 场景 | 回归用例 |
|---|---|---|
| `POST /chat` | 同步 `.call()` + RAG + tools | G7 |
| `POST /chat/stream-plain` | 顶层 `.stream()`，纯 LLM | G1-plain |
| `POST /chat/stream-rich` | 顶层 `.stream()` + RAG + tool | G1-rich |
| `POST /chat/memory` | `ChatMemory` 多轮对话 | G2 |
| `POST /error-demo/soft` | tool 抛异常，被默认 processor 吞掉 | G3-soft |
| `POST /error-demo/hard` | tool 抛异常，自定义 processor 重新抛出 | G3-hard |
| `POST /subagent-deep-demo` | 3 层直链嵌套子 Agent | G4 |
| `POST /multi-agent-demo` | 同一请求内两个兄弟 ChatClient 顺序调用 | G6 |
| `POST /subagent-demo` | 外层 `.call()` + 内层 `.call()` | G8 |
| `POST /subagent-demo/stream` | 外层 `.stream()` + 内层 `.call()` | G9 |
| `POST /subagent-demo/stream-deep` | 外层 `.stream()` + 内层 `.stream()` | G10 |
| `GET /sequential-stream-demo` | 同一请求内顺序 4 次顶层 `.stream()`（无请求体） | G11 |

一键跑全部回归：仓库根目录执行 `./scripts/run-regression.sh`，详见
[`docs/testing/regression-playbook.md`](../docs/testing/regression-playbook.md)。

---

## 四、在 CLS 控制台看 trace

1. 打开腾讯云 CLS 控制台 → 日志服务 → 你的日志主题
2. 选择 **GenAI Trace** 标签页
3. 筛选条件：`gen_ai.session.id = demo-weather-001`（或你自己传的 session id）
4. 应该能看到一棵完整的 span 树；点开每个 span 可以看到完整的
   `gen_ai.input.messages`、`gen_ai.output.messages`、`gen_ai.tool.call.arguments`
   等字段

---

## 五、模块结构

```
demo-springboot/
├── pom.xml                              # 继承 spring-boot-starter-parent:4.1.1，Spring AI BOM 2.0.1
├── README.md                            # 本文件
└── src/main/
    ├── java/com/tencentcloudapi/observability/springai/demo/boot/
    │   ├── DemoApplication.java         # @SpringBootApplication
    │   ├── config/
    │   │   ├── ClsObsConfig.java        # 把 SDK 挂到 ObservationRegistry
    │   │   ├── ChatMemoryConfig.java    # ChatMemory（/chat/memory 使用）
    │   │   └── VectorStoreConfig.java   # SimpleVectorStore + ingest + 持久化
    │   ├── controller/
    │   │   ├── ChatController.java                  # /chat、/chat/stream-*、/chat/memory
    │   │   ├── ErrorToolsDemoController.java        # /error-demo/soft|hard
    │   │   ├── MultiAgentDemoController.java        # /multi-agent-demo
    │   │   ├── SubAgentDemoController.java          # /subagent-demo[/stream|/stream-deep]
    │   │   ├── SubAgentDeepDemoController.java      # /subagent-deep-demo
    │   │   └── SequentialStreamDemoController.java  # /sequential-stream-demo
    │   ├── service/
    │   │   ├── AgentService.java        # ChatClient + Tools + QuestionAnswerAdvisor
    │   │   └── MemoryChatService.java   # 带 ChatMemory 的 ChatClient
    │   └── tools/
    │       ├── WeatherTools.java        # @Tool getWeather(city)
    │       └── TimeTools.java           # @Tool getCurrentTime(timezone)
    └── resources/
        ├── application.yml              # Hunyuan endpoint / model / observations
        ├── logback-spring.xml           # 日志级别
        └── docs/                        # RAG 初始 ingest 的 3 篇文档
            ├── cls.md
            ├── spring-ai.md
            └── otel.md
```

---

## 六、FAQ

### Q1：如何换一个 chat 模型？

tokenhub 实测可用：`hy4-preview`（推荐）、`hy3`、`deepseek-v4-pro`、`kimi-k3`、
`glm-5.3` 等。改 `src/main/resources/application.yml` 的
`spring.ai.openai.chat.options.model` 即可。注意不是所有模型都支持 function
calling，demo 里的 Tool 场景需要选支持 function calling 的模型。

### Q2：如何换一个 embedding 模型？

tokenhub 实测可用：`kinfra-text-embedding-0.6b`（1024 维，推荐）、
`kinfra-text-embedding-4b`（更大更准）。改 `application.yml` 的
`spring.ai.openai.embedding.options.model`。

**换 embedding 模型后，必须删掉 `demo-springboot/vector-store.json` 重启**，
否则持久化文件里的旧向量和新模型维度/语义不一致，RAG 召回会错。

### Q3：如何扩展知识库？

把更多 `.md` 文件放到 `src/main/resources/docs/`，删掉 `vector-store.json`
重启即可。首次启动会把全部文件切片 + embedding + 持久化。

### Q4：切回腾讯云官方混元端点？

把 `application.yml` 里的 `base-url` 改回 `https://api.hunyuan.cloud.tencent.com`，
chat 模型改成 `hunyuan-turbos-latest` / `hunyuan-pro` / `hunyuan-standard`，
embedding 改成 `hunyuan-embedding`。API Key 格式也是 `sk-xxx`。

### Q5：如何关掉 RAG，只看 chat / tool 链路？

```bash
mvn -f demo-springboot/pom.xml spring-boot:run \
  -Dspring-boot.run.arguments="--demo.rag.enabled=false"
```

此时不会注册 `VectorStore` bean，也不会挂载 `QuestionAnswerAdvisor`，trace 中
不再出现 `embedding` / `retrieval` span，适合单独验证 ReAct 轮次切换。

### Q6：怎么关掉 SDK 的 debug 日志？

把 `demo-springboot/src/main/java/.../config/ClsObsConfig.java` 里
`ClsConfig.builder()....debug(true)` 改成 `.debug(false)`；并把
`logback-spring.xml` 里 `com.tencentcloudapi.observability` 的 level 从
`DEBUG` 改成 `INFO`。

---

## 七、这个 demo 不参与 SDK 的发布流程

`demo-springboot/` 和 `demo/` 一样，仅作为端到端验证样例存在：

- 没有 `maven-shade-plugin`
- 没有 `maven-gpg-plugin` 签名
- 没有 Central Publishing 配置

它只是一个"演示型"子工程，并没有被根 `pom.xml` 的 `<modules>` 聚合，所以
`mvn deploy` 不会把它推到任何仓库。
