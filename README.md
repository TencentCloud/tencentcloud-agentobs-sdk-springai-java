# tencentcloud-agentobs-sdk-springai-java

> **Language / 语言**：**English** · [中文](README.zh-CN.md)

**Tencent Cloud CLS observability SDK for Spring AI.**

Mirrors every Micrometer `Observation` that Spring AI emits natively
(`ChatClient` / `ChatModel` / `Advisor` / `ToolCallback` / `EmbeddingModel` /
`VectorStore`) into a CLS-GenAI-Trace-compliant OpenTelemetry span tree and
ships it to Tencent Cloud CLS over the standard **OTLP/HTTP** protocol.

- **No bytecode instrumentation** — pure library, no javaagent required
- **One-line setup** — `ClsObservability.install().registerOn(observationRegistry)`
- **Shaded OTel SDK** — coexists safely with ARMS javaagent or any other
  in-JVM OpenTelemetry implementation; nobody can hijack this SDK's Tracer
- **Bypasses the `include-prompt` toggle** — prompt / completion / tool
  arguments are read directly off `ObservationContext`, so you get full
  content without flipping Spring AI's privacy switch
- **CLS GenAI Trace compliant** — `entry → agent → react → chat / tool`
  span tree, isomorphic to the sibling AgentScope SDK
- **Sub-agents, multi-round ReAct and streaming** — nested `ChatClient`s
  inside tools become `invoke_subagent` spans; every tool-call round gets
  its own `react round_N` step; `.stream()` traces stay intact across
  Reactor worker threads

---

## 1. Compatibility

| Component | Supported |
|---|---|
| JDK | 17+ |
| Spring AI | 1.0.x and 2.0.x (compiled against 1.0.0; `demo-springboot` runs on 2.0.1) |
| Spring Boot | 3.x / 4.x (`demo-springboot` runs on 4.1.1) |
| Micrometer Observation | whatever your Spring Boot brings in |

---

## 2. Maven dependency

```xml
<dependency>
    <groupId>com.tencentcloudapi.observability</groupId>
    <artifactId>tencentcloud-agentobs-sdk-springai-java</artifactId>
    <version>1.0.1</version>
</dependency>
```

Your host application must already provide the following (the SDK declares
them as `provided`, so it never forces a version on you):

- `spring-ai-client-chat` / `spring-ai-model`
- `micrometer-observation`

---

## 3. Configuration

### Required

| Env var | Description |
|---|---|
| `CLS_ENDPOINT` | CLS OTLP endpoint, e.g. `https://ap-guangzhou.cls.tencentcs.com` (`https://` is prepended if no scheme is given) |
| `CLS_TOPIC_ID` | CLS log topic ID |
| `CLS_SECRET_ID` | Tencent Cloud SecretId |
| `CLS_SECRET_KEY` | Tencent Cloud SecretKey |

`install()` fails fast with an `IllegalStateException` listing the missing
fields if any of the four is absent.

### Optional

| Env var | Builder method | Default | Description |
|---|---|---|---|
| `CLS_SERVICE_NAME` | `serviceName` | `springai-app` | Resource `service.name` |
| `CLS_SERVICE_VERSION` | `serviceVersion` | — | Resource `service.version` |
| `CLS_SERVICE_INSTANCE_ID` | `serviceInstanceId` | `<hostname>@<pid>` | Resource `service.instance.id` |
| `CLS_DEPLOYMENT_ENVIRONMENT` | `deploymentEnvironment` | — | Resource `deployment.environment.name` |
| `CLS_SOURCE` | `source` | local IP | Resource `host.ip` |
| `CLS_BATCH_SIZE` | `batchSize` | `32` (clamped to 1..1000) | `BatchSpanProcessor` max export batch size |
| `CLS_SAMPLE_RATIO` | `sampleRatio` | `1.0` (clamped to 0.0..1.0) | Head-based trace sampling — see [§6](#6-trace-sampling) |
| `CLS_DEBUG` | `debug` | `false` | Log each observation's start / stop at SLF4J `DEBUG` |
| `CLS_SPRINGAI_REACTOR_CONTEXT_PROPAGATION` | `reactorContextPropagation` | `true` | See [§7](#7-streaming--reactor) |
| `CLS_CUSTOM_<KEY>` | `customAttribute(key, value)` | — | Written as `agent.custom.<key>` (key lower-cased) |

### Resolution order (highest priority first)

1. `ClsConfig.builder()...` explicit values
2. Process environment variables
3. The first file found among `./.env` and `~/.agentobs-springai/config.env`
   (never overrides real env vars)

> Keep credentials in environment variables or a `.env` file that is
> excluded from version control (`.env` is already in this repo's
> `.gitignore`). Never hard-code `SecretId` / `SecretKey` in source.

Spans are uploaded to `<CLS_ENDPOINT>/v1/traces` with
`Authorization: Basic base64(SecretId:SecretKey)` and a `topic_id` header.

---

## 4. Wiring

### Spring Boot

```java
import com.tencentcloudapi.observability.springai.ClsObservability;
import io.micrometer.observation.ObservationRegistry;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class ClsObservabilityConfig {

    @Bean(destroyMethod = "shutdown")   // flush pending spans on context close
    public ClsObservability clsObservability(ObservationRegistry observationRegistry) {
        ClsObservability obs = ClsObservability.install();   // env / .env
        obs.registerOn(observationRegistry);                 // Spring AI's registry
        return obs;
    }
}
```

With explicit settings:

```java
ClsObservability obs = ClsObservability.install(
        ClsConfig.builder()
                .serviceName("order-assistant")
                .serviceVersion("2.3.0")
                .deploymentEnvironment("prod")
                .customAttribute("project_id", "proj_001")
                .build());   // credentials still come from env / .env
```

> **Important**: register the handler on the **same** `ObservationRegistry`
> bean that Spring AI uses. A freshly `new`-ed registry receives no Spring AI
> events. Spring Boot normally exposes this bean through Actuator's
> auto-configuration. If your app has no `ObservationRegistry` bean, declare
> one with `@ConditionalOnMissingBean`, as
> [`demo-springboot`'s `ClsObsConfig`](demo-springboot/src/main/java/com/tencentcloudapi/observability/springai/demo/boot/config/ClsObsConfig.java)
> does.

### Plain Java

```java
ClsObservability obs = ClsObservability.install();
obs.registerOn(registry);   // the registry your ChatClient / ChatModel uses
// ...
obs.flush();      // optional, force-flush pending spans
obs.shutdown();   // also runs automatically in a JVM shutdown hook
```

### Propagate session / user identity (strongly recommended)

```java
import com.tencentcloudapi.observability.springai.ClsBaggage;

@PostMapping("/chat")
public String chat(@RequestHeader("X-Session-Id") String sessionId,
                   @RequestHeader("X-User-Id")    String userId,
                   @RequestBody String prompt) {
    ClsBaggage.set(sessionId, userId);          // or set(sessionId, userId, userName)
    try {
        return chatClient.prompt().user(prompt).call().content();
    } finally {
        ClsBaggage.clear();
    }
}
```

| API | Span attribute |
|---|---|
| `ClsBaggage.set(sessionId, userId[, userName])` | all three at once |
| `ClsBaggage.setSession(id)` | `gen_ai.session.id` |
| `ClsBaggage.setUser(id)` | `gen_ai.user.id` |
| `ClsBaggage.setUserName(name)` | `gen_ai.user.name` |

Baggage is captured when the turn starts on the calling thread, so you do
**not** need to propagate it through Reactor operators for `.stream()`.
If you skip `ClsBaggage`, the SDK generates a `sess-<16 hex>` session id —
the trace is still complete, but you can't aggregate it per user / session
in the CLS console.

---

## 5. Produced span tree

```
enter_application                     SERVER    span.kind=entry      ← one per top-level ChatClient call
└─ invoke_agent main                  INTERNAL  span.kind=agent      agent.scope=main
   ├─ react round_1                   INTERNAL  span.kind=step       ← first Advisor (nested advisors are collapsed)
   │  ├─ chat <model>                 CLIENT    span.kind=chat       ← ChatModel call
   │  ├─ execute_tool <tool>          CLIENT    span.kind=tool       ← ToolCallback
   │  │  └─ invoke_subagent <tool>    INTERNAL  span.kind=agent      agent.scope=subagent ← nested ChatClient in a tool
   │  │     └─ react round_1 ─ chat / execute_tool ...
   │  ├─ embedding                    CLIENT    span.kind=step       ← EmbeddingModel
   │  └─ retrieval <store>            INTERNAL  span.kind=retriever  ← VectorStore query
   └─ react round_2 .. N              INTERNAL  span.kind=step       ← one more per tool-call round
      └─ chat <model> / execute_tool <tool> ...
```

- **ReAct rounds**: when a `chat` finishes with `finish_reason=tool_calls`,
  the next `chat` / `embedding` opens a new `react round_N` sibling step.
- **Sub-agents**: a `ChatClient` invoked inside a tool becomes
  `invoke_subagent <toolName>` (fallback `subagent-N`), shares the outer
  `session.id` / `turn.id`, and links back via
  `gen_ai.subagent.parent_tool_call.id`. Arbitrary nesting depth is supported,
  for both `.call()` and `.stream()` on either side.
- **Sibling ChatClients**: multiple top-level `ChatClient` calls made one
  after another in the same request produce **independent traces**.
- Other observations map to `step` spans: `image_generation`,
  `add_documents` / `delete_documents` (VectorStore writes), or the raw
  observation name.

### Identity attributes

| Attribute | Value |
|---|---|
| `gen_ai.span.kind` | `entry` / `agent` / `step` / `chat` / `tool` / `retriever` |
| `gen_ai.operation.name` | `enter_application` / `invoke_agent` / `invoke_subagent` / `react` / `chat` / `execute_tool` / `embedding` / `retrieval` / ... |
| `gen_ai.agent.type` | constant `spring-ai` |
| `gen_ai.agent.name` / `gen_ai.agent.scope` | `main` / `main`, or `<toolName>` / `subagent` |
| `gen_ai.session.id` | from `ClsBaggage`, else `sess-<16 hex>` |
| `gen_ai.turn.id` | `{sessionId}:t{N}` — N increments per session |
| `gen_ai.step.id` / `gen_ai.react.round` | `{turnId}:s{N}` / round number (react and its children) |
| `gen_ai.user.id` / `gen_ai.user.name` | from `ClsBaggage` (optional) |
| `gen_ai.request.streaming` | `true` on entry / agent / chat spans of a `.stream()` turn (omitted for `.call()`) |
| `agent.custom.<key>` | from `customAttribute` / `CLS_CUSTOM_*` (on react / chat / tool / embedding / retrieval spans) |

### Content and metrics attributes

| Span | Attributes |
|---|---|
| entry / agent / react / chat | `gen_ai.input.messages`, `gen_ai.output.messages` — OpenAI-chat-shaped JSON arrays (OTel GenAI SemConv v1.37+), truncated at 8000 chars |
| chat | `gen_ai.request.model` / `temperature` / `top_p` / `top_k` / `max_tokens` / `frequency_penalty` / `presence_penalty` / `stop_sequences` / `seed`, `gen_ai.response.finish_reasons`, `gen_ai.usage.*` (Spring AI native), `gen_ai.chat.duration_ms` |
| tool | `gen_ai.tool.name` / `type` / `call.id` / `call.arguments` / `call.result`, `gen_ai.tool.call.duration_ms`, `gen_ai.tool.error.type` (on error) |
| react | `gen_ai.react.finish_reason` |
| embedding | `gen_ai.embeddings.purpose` / `input` / `input_count` / `vector_count` / `vector_dimensions` |
| retrieval | `gen_ai.retrieval.query_text`, `gen_ai.request.top_k`, `gen_ai.retrieval.documents.count`, `gen_ai.retrieval.duration_ms` |
| invoke_subagent | `gen_ai.subagent.parent_tool_call.id` |

All Micrometer low / high cardinality key-values Spring AI attaches are
copied onto the span as well. On error, the span records the exception
and is set to `ERROR`; a root turn's error also propagates to its
`enter_application` span.

### Resource attributes

`service.name` / `service.version` / `service.instance.id`,
`deployment.environment.name`, `host.name` / `host.ip`, `process.pid` /
`process.runtime.*`, `os.type` / `os.description` / `os.version`,
`telemetry.sdk.*`.

---

## 6. Trace sampling

By default every turn is uploaded (`CLS_SAMPLE_RATIO=1.0`). Lower the ratio
to cut upload volume and CLS storage:

```bash
export CLS_SAMPLE_RATIO=0.1     # keep roughly 1 turn in 10
```

```java
ClsObservability.install(
        ClsConfig.builder()
                .sampleRatio(0.1)
                .build());
```

Resolution order: builder → `-Dcls.springai.sample-ratio` → `CLS_SAMPLE_RATIO`
→ `.env` → `1.0`. Values outside `[0.0, 1.0]` are clamped; unparseable values
fall back to `1.0` (losing telemetry is the worse failure mode).

**The decision is per turn, and a turn is kept whole or dropped whole.** Only
`enter_application` is a trace root; every other span — agent, react, chat,
tool, embedding, retrieval, and any nested `invoke_subagent` subtree — is
created with an explicit parent and inherits the root's verdict through OTel's
`ParentBased` sampler. CLS therefore never renders a trace with holes.

Two caveats before you turn this on:

- **Per-turn, not per-session.** Each top-level `ChatClient` call starts a new
  trace id, so a long conversation ends up with gaps between its turns. If
  per-session auditability matters more than volume, keep `1.0`.
- **It saves upload and storage, not CPU.** Sampled-out turns still run
  through the handler's state machine and still build their attribute
  strings; only the export is skipped.

`CLS_SAMPLE_RATIO=0` drops everything and logs a warning at startup.

---

## 7. Streaming / Reactor

`ChatClient.stream()` runs Spring AI's inner observations on Reactor
scheduler threads. To keep them in one trace, `install()` reflectively calls
`reactor.core.publisher.Hooks.enableAutomaticContextPropagation()` (no
compile-time dependency on `reactor-core`; a no-op if Reactor is absent).
The hook is JVM-global and idempotent.

Opt out (e.g. you already enable it yourself) with any of:

- `ClsConfig.builder().reactorContextPropagation(false)`
- `-Dcls.springai.reactor-context-propagation=false` (beats the env var)
- `CLS_SPRINGAI_REACTOR_CONTEXT_PROPAGATION=false`

---

## 8. Coexisting with ARMS / other OTel implementations

All `io.opentelemetry.*` classes (plus OkHttp / Okio / Protobuf) are
relocated to `com.tencentcloudapi.observability.shaded.*` at build time:

- An ARMS javaagent pattern-matches on `io.opentelemetry.*` and **cannot**
  see this SDK's Tracer, so our spans are never hijacked.
- The internal `OpenTelemetrySdk` is created with `build()`, not
  `buildAndRegisterGlobal()`, so it never fights the host app for the
  `GlobalOpenTelemetry` singleton.
- Only `io.micrometer.observation.*` is deliberately **not** shaded — the
  SDK must share that SPI with Spring AI to receive events.

**Known trade-off**: if a worker thread carries both an APM parent context
and residual state from a previous `.stream()` turn, the SDK starts the new
turn from `Context.root()` to keep CLS traces independent, which drops the
APM linkage for that request. See [CHANGELOG](CHANGELOG.md) `0.0.12`.

---

## 9. Local verification (`demo/`)

A standalone demo in `demo/` fires a synthetic Spring-AI-shaped observation
tree (no real LLM, no LLM network traffic — only the upload to CLS) and
produces exactly **6 spans**.

```bash
# 1) Configure CLS (or put these in ~/.agentobs-springai/config.env)
export CLS_ENDPOINT=https://ap-guangzhou.cls.tencentcs.com
export CLS_TOPIC_ID=<your-topic-id>
export CLS_SECRET_ID=<your-secret-id>
export CLS_SECRET_KEY=<your-secret-key>

# 2) Install the SDK into your local Maven repo (from the repo root)
mvn -q -DskipTests -Dgpg.skip=true install

# 3) Build & run the demo
cd demo
mvn -q -DskipTests package
java -jar target/springai-demo-shaded.jar
```

The console prints the `sessionId`. In the CLS console → GenAI Trace,
filter by `gen_ai.session.id = <printed value>`:

| # | span name | `gen_ai.span.kind` | parent | key fields |
|---|---|---|---|---|
| 1 | `enter_application` | `entry` | *(root)* | `session.id`, `turn.id`, `user.id`, `input.messages` |
| 2 | `invoke_agent main` | `agent` | #1 | `agent.name=main`, `agent.scope=main`, `output.messages` |
| 3 | `react round_1` | `step` | #2 | `step.id=<session>:t1:s1`, `react.round=1` |
| 4 | `chat gpt-4o` | `chat` | #3 | `usage.input_tokens=42`, `usage.output_tokens=17` |
| 5 | `execute_tool get_weather` | `tool` | #3 | `tool.call.arguments={"city":"Beijing"}`, `tool.call.result=...` |
| 6 | `chat gpt-4o` | `chat` | #3 | `usage.input_tokens=60`, `usage.output_tokens=25` |

---

## 10. Real end-to-end demo (`demo-springboot/`)

A Spring Boot 4 + Spring AI 2.0 web app that calls the real Tencent
Hunyuan LLM (`hy4-preview` via tokenhub), with two `@Tool`s, local-vector-store
RAG, `ChatMemory`, sub-agents and streaming endpoints.

```bash
set -a; source ~/.agentobs-springai/config.env; set +a    # HUNYUAN_API_KEY + CLS_*
mvn -q -DskipTests -Dgpg.skip=true install                # install SDK
mvn -f demo-springboot/pom.xml spring-boot:run            # run demo on :8080

curl -sS -X POST http://localhost:8080/chat \
  -H 'Content-Type: application/json' \
  -H 'X-Session-Id: demo-weather-001' -H 'X-User-Id: alice' \
  -d '{"q":"北京今天天气怎么样？"}'
```

| Endpoint | Scenario |
|---|---|
| `POST /chat` | `.call()` + RAG + tools |
| `POST /chat/stream-plain` · `/chat/stream-rich` | top-level `.stream()`, plain / with RAG + tools |
| `POST /chat/memory` | `ChatMemory` multi-turn |
| `POST /error-demo/soft` · `/error-demo/hard` | tool exception swallowed / rethrown |
| `POST /subagent-demo` · `/stream` · `/stream-deep` | sub-agent: call+call / stream+call / stream+stream |
| `POST /subagent-deep-demo` | 3-layer nested sub-agents |
| `POST /multi-agent-demo` | two sibling ChatClients in one request |
| `GET /sequential-stream-demo` | four sequential top-level `.stream()` calls in one request |

See [`demo-springboot/README.md`](demo-springboot/README.md) for details.

---

## 11. Troubleshooting

Enable `CLS_DEBUG=1` (or `.debug(true)`) and set the
`com.tencentcloudapi.observability` logger to `DEBUG` to see lines such as
`onStart  TURN  session=... turn=... traceId=...`.

| Symptom | Cause | Fix |
|---|---|---|
| `IllegalStateException: CLS configuration incomplete` | a required field is missing | set the four `CLS_*` variables |
| No spans at all | handler registered on the wrong registry | call `registerOn(...)` with the registry Spring AI actually uses |
| No trace on CLS | wrong endpoint / topic / credentials | check the `installed (service=..., endpoint=..., topic=..., sampleRatio=...)` startup log |
| Only some turns appear on CLS | sampling is active | check `sampleRatio` in the startup log; set `CLS_SAMPLE_RATIO=1.0` to keep everything |
| `.stream()` spans split into many traces | Reactor propagation disabled or failed | check the startup log for `enabled Reactor automatic context propagation` |
| `session.id` is `sess-...` | `ClsBaggage` not set | call `ClsBaggage.set(...)` before the `ChatClient` call |
| Spring AI's `gen_ai.prompt` is empty | `include-prompt` is off by default | use the SDK's `gen_ai.input.messages` / `gen_ai.output.messages` |

---

## 12. Regression suite

[`scripts/run-regression.sh`](scripts/run-regression.sh) runs 12 cases
(13 sub-cases, G1–G10) against `demo-springboot`: top-level sync / stream,
ReAct round-switch, ChatMemory, tool error swallow / rethrow, nested and
3-layer sub-agents, 10-way concurrent streaming, sibling ChatClients, and
Reactor cross-thread sub-agents.

```bash
./scripts/run-regression.sh
```

It installs the SDK, starts the demo, runs every case, parses the server
log and prints a PASS / FAIL table. See
[`docs/testing/regression-playbook.md`](docs/testing/regression-playbook.md).

---

## Changelog

See [CHANGELOG.md](CHANGELOG.md).

## License

[Apache License 2.0](LICENSE).
