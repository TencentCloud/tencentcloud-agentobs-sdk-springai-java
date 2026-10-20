# demo-springboot — 真实端到端 Spring Boot 3 + Spring AI 1.0 Demo

> 本模块是和 `demo/`（纯 Mock、无网络）并列的**另一种 demo**：它跑真实的
> Spring Boot 3 Web 应用，接真实的腾讯云混元大模型，带两个真实的 `@Tool`
> 函数和基于本地向量库的真实 RAG，让本仓库 CLS SDK 在**最接近线上形态**的
> 环境下完整采集并上报 trace 到腾讯云 CLS GenAI Trace。

| 对比 | `demo/` | **`demo-springboot/`（本模块）** |
|---|---|---|
| 形态 | 单 jar CLI | Spring Boot 3 Web 应用（`POST /chat`） |
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

**预期 CLS trace 树**（在控制台搜 `gen_ai.session.id = demo-weather-001`）：

```
entry (POST /chat)
└── agent (spring-ai)
    └── step (QuestionAnswerAdvisor)       ← RAG advisor 本身也走了，但召回无关
        ├── chat (hy4-preview, finish_reason=tool_calls)
        ├── tool (getWeather, args={"city":"北京"})
        └── chat (hy4-preview, finish_reason=stop)
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
├── pom.xml                              # 继承 spring-boot-starter-parent:3.3.4
├── README.md                            # 本文件
└── src/main/
    ├── java/com/tencentcloudapi/observability/springai/demo/boot/
    │   ├── DemoApplication.java         # @SpringBootApplication
    │   ├── config/
    │   │   ├── ClsObsConfig.java        # 把 SDK 挂到 ObservationRegistry
    │   │   └── VectorStoreConfig.java   # SimpleVectorStore + ingest + 持久化
    │   ├── controller/
    │   │   └── ChatController.java      # POST /chat，透传 session/user baggage
    │   ├── service/
    │   │   └── AgentService.java        # ChatClient + Tools + QuestionAnswerAdvisor
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

### Q5：为什么 CLS trace 里没看到独立的 `retrieval` / `embedding` span？

Spring AI 1.0 的 `SimpleVectorStore` 内部的 `similaritySearch` 和
`embeddingModel.embed()` **没有自带 Observation 发射点**（而 `PgVectorStore`、
`RedisVectorStore` 等生产级实现有）。所以本 demo 的 trace 里 RAG 检索会被
"折叠"进 `advisor` step 里，表现为 step 比纯 Tool 场景多出一个。要触发独立
`retrieval` / `embedding` span，可以：
- 换成 `PgVectorStore` 等 observability-ready 实现
- 或在业务代码里显式调用一次 `embeddingModel.embed(text)`

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
