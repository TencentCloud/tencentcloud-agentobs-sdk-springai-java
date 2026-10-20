# tencentcloud-agentobs-sdk-springai-java

> **Language / 语言**：**English** · [中文](README.zh-CN.md)

**Tencent Cloud CLS observability SDK for Spring AI 1.x.**

Mirrors every Spring AI–flavoured Micrometer `Observation` (`ChatClient` /
`ChatModel` / `Advisor` / `ToolCallback` / `EmbeddingModel` / `VectorStore`)
into a CLS-GenAI-Trace-compliant OpenTelemetry span tree and ships it to
Tencent Cloud CLS over the standard **OTLP/HTTP** protocol.

- ✅ **No bytecode instrumentation** — pure SDK, no javaagent required
- ✅ **One-line setup** — `ClsObservability.install().registerOn(observationRegistry)`
- ✅ **Shaded OTel SDK** — coexists safely with ARMS javaagent / any other
  in-JVM OTel implementation; nobody can hijack this SDK's Tracer
- ✅ **Bypasses the `include-prompt` toggle** — prompt / completion / tool
  arguments are read directly off `ObservationContext` via reflection, so you
  get full content without flipping the privacy switch
- ✅ **CLS GenAI Trace compliant** — produces the `entry → agent → step → chat / tool`
  span tree, isomorphic to the sibling AgentScope SDK

---

## 1. Maven dependency

```xml
<dependency>
    <groupId>com.tencentcloudapi.observability</groupId>
    <artifactId>tencentcloud-agentobs-sdk-springai-java</artifactId>
    <version>0.0.9</version>
</dependency>
```

Your host application must already provide (this SDK pulls them as `provided`,
so it won't force a version on you):
- `spring-ai-client-chat` / `spring-ai-model` — Spring AI 1.x
- `micrometer-observation` — bundled with Spring Boot 3.x by default

---

## 2. CLS configuration (four required fields)

| Env var | Description |
|---|---|
| `CLS_ENDPOINT` | e.g. `https://<region>.cls.tencentcs.com` |
| `CLS_TOPIC_ID` | CLS log topic ID |
| `CLS_SECRET_ID` | Tencent Cloud access key ID |
| `CLS_SECRET_KEY` | Tencent Cloud access key secret |

Optional: `CLS_SERVICE_NAME`, `CLS_SOURCE`, `CLS_BATCH_SIZE`, `CLS_DEBUG`.

**Three config sources** (highest priority first):
1. `ClsConfig.builder().endpoint(...)` — explicit values
2. Process environment variables
3. `./.env` or `~/.agentobs-springai/config.env`

---

## 3. Wiring (idiomatic Spring Boot)

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
        ClsObservability.install()                 // reads env / .env / builder
                .registerOn(observationRegistry);  // attach to Spring AI's registry
    }
}
```

> **Important**: register the handler onto the *same* `ObservationRegistry`
> bean Spring AI actually uses (Spring Boot auto-config exposes a global bean
> named `observationRegistry`). If you `new` a fresh registry and register
> there, no Spring AI events will reach the SDK.

### Non-Spring-Boot usage

```java
ClsObservability obs = ClsObservability.install();
ObservationRegistry registry = /* your registry */;
obs.registerOn(registry);
```

---

## 4. Produced span tree (CLS GenAI Trace spec)

The SDK produces an **isomorphic span tree to the AgentScope SDK**, in full
compliance with the CLS GenAI Trace spec:

```
entry   (SERVER, gen_ai.span.kind=entry)        ← one per ChatClient call
  └─ agent (INTERNAL, gen_ai.span.kind=agent)
       ├─ step (INTERNAL, gen_ai.span.kind=step)  ← one per Advisor
       │   ├─ chat (CLIENT, gen_ai.span.kind=chat)   ← ChatModel call
       │   └─ tool (CLIENT, gen_ai.span.kind=tool)   ← tool / function call
       ├─ step (operation.name=embedding | retrieval)
       ├─ chat (ChatModel called directly off ChatClient)
       └─ tool (tool  called directly off ChatClient)
```

### Spec-mandated attributes written on every span

| Attribute | Description |
|---|---|
| `gen_ai.span.kind` | `entry` / `agent` / `step` / `chat` / `tool` |
| `gen_ai.operation.name` | `enter_application` / `invoke_agent` / `react` / `chat` / `execute_tool` / `embedding` / `retrieval` |
| `gen_ai.agent.type` | constant `spring-ai` |
| `gen_ai.session.id` | read from `ClsBaggage.setSession()`; falls back to UUID when absent |
| `gen_ai.turn.id` | `{sessionId}:t{N}` |
| `gen_ai.step.id` | `{turnId}:s{N}` (step / chat / tool) |
| `gen_ai.user.id` | read from `ClsBaggage.setUser()` (optional) |

### Content fields (reflected off `ObservationContext`, no `include-prompt` required)

| Attribute | Where | Value shape |
|---|---|---|
| `gen_ai.input.messages` | entry / agent / chat / step (request side) | **JSON array** per OpenTelemetry GenAI SemConv v1.37+: `[{"role":"system","content":"..."},{"role":"user","content":"..."}]` |
| `gen_ai.output.messages` | entry / agent / chat / step (response side) | **JSON array**: `[{"role":"assistant","content":"...","tool_calls":[{"id":"...","type":"function","function":{"name":"...","arguments":"..."}}]}]` |
| `gen_ai.tool.name` / `gen_ai.tool.type` / `gen_ai.tool.call.id` | tool |  |
| `gen_ai.tool.call.arguments` / `gen_ai.tool.call.result` | tool | arguments is a JSON object string, result is the raw tool return value as string |
| `gen_ai.tool.error.type` | tool (on exception) |  |
| `gen_ai.usage.{input_tokens, output_tokens, total_tokens}` | chat (Spring AI native) |  |
| `gen_ai.finish_reason` / `gen_ai.turn.completed` | entry / agent (on stop) |  |

The {@code gen_ai.input.messages} / {@code gen_ai.output.messages} fields are
produced by a built-in `ClsGenAIFormatter` that converts Spring AI's native
`Prompt` / `ChatResponse` objects into the OpenAI-chat JSON shape the CLS
console expects — so each message renders as its own chat-bubble, not a single
opaque text blob.

### Propagate `session.id` / `user.id` (strongly recommended)

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

Without this, the SDK auto-generates a `sess-xxxxxxxxxxxxxxxx` session id,
so the trace tree is still complete — you just lose the ability to aggregate
per-user / per-session on the CLS console.

---

## 5. Local verification (`demo/` sub-module)

A standalone end-to-end verification demo lives in `demo/`. It fires a
synthetic Spring-AI-shape observation tree (no real LLM, no network traffic)
that fully exercises every code path in the handler and produces exactly
**6 spans** on CLS: `entry + agent + step + chat × 2 + tool`.

### Run

```bash
# 1) Configure CLS
export CLS_ENDPOINT=https://ap-guangzhou.cls.tencentcs.com
export CLS_TOPIC_ID=xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx
export CLS_SECRET_ID=AKID...
export CLS_SECRET_KEY=...

# 2) Install the main SDK into your local Maven repo
mvn -q -DskipTests -Dgpg.skip=true install

# 3) Build & run the demo
cd demo
mvn -q -DskipTests package
java -jar target/springai-demo-shaded.jar
```

### Expected console output

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

### What to verify on the CLS console

Open the CLS console → GenAI Trace → filter by
`gen_ai.session.id = <the printed value>`. You should see one trace with
6 spans and the following parent/child structure:

| # | span name | `gen_ai.span.kind` | `gen_ai.operation.name` | parent | key fields |
|---|---|---|---|---|---|
| 1 | `enter_application` | `entry` | `enter_application` | *(root)* | `session.id`, `turn.id`, `user.id`, `input.messages` |
| 2 | `invoke_agent` | `agent` | `invoke_agent` | #1 entry | + `output.messages`, `finish_reason`, `turn.completed` |
| 3 | `react` | `step` | `react` | #2 agent | `step.id` (e.g. `demo-session-xxx:t1:s1`) |
| 4 | `chat` | `chat` | `chat` | #3 step | `request.model=gpt-4o`, `usage.input_tokens=42`, `usage.output_tokens=17`, `finish_reasons=tool_calls` |
| 5 | `execute_tool` | `tool` | `execute_tool` | #3 step | `tool.name=get_weather`, `tool.call.arguments={"city":"Beijing"}`, `tool.call.result={"city":"Beijing","temp":"22C",…}` |
| 6 | `chat` | `chat` | `chat` | #3 step | `usage.input_tokens=60`, `usage.output_tokens=25`, `finish_reasons=stop` |

If all 6 spans appear with the exact parent/child structure above, the SDK
is wired correctly for your environment.

### Troubleshooting

| Symptom | Cause | Fix |
|---|---|---|
| No trace on CLS | wrong endpoint / topic / secret | enable `CLS_DEBUG=1` to log the full upload URL + response code |
| Only 1 span per trace | handler registered on the wrong registry | ensure `obs.registerOn(registry)` uses the *same* registry instance that actually runs the observations |
| `session.id` column empty | `ClsBaggage.set(...)` never called | add `ClsBaggage.set(sid, uid)` in your controller |

---

## 6. Real end-to-end demo (`demo-springboot/` sub-module)

A sibling demo module `demo-springboot/` runs a **real Spring Boot 3 Web
application** that calls the actual Tencent Hunyuan LLM, exercises
`@Tool` function calling and local-vector-store RAG — i.e. the closest
possible shape to a production Spring AI app. The SDK captures the full
chain to CLS GenAI Trace automatically.

| | `demo/` | `demo-springboot/` |
|---|---|---|
| Shape | Fat jar CLI | Spring Boot 3 Web (`POST /chat`) |
| LLM | Mock (synthetic observations) | **Real** — Hunyuan `hy4-preview` via tokenhub |
| Embedding | — | **Real** — `kinfra-text-embedding-0.6b` (1024-dim) |
| Tools | — | 2 × `@Tool` (weather / current time) |
| RAG | — | `SimpleVectorStore` + local JSON persistence |
| Network | not required | required (Hunyuan + CLS) |

```bash
# One-shot:
set -a; source ~/.agentobs-springai/config.env; set +a
mvn -q -DskipTests -Dgpg.skip=true install                   # install SDK
mvn -f demo-springboot/pom.xml spring-boot:run                # run demo

# Trigger a trace:
curl -sS -X POST http://localhost:8080/chat \
  -H 'Content-Type: application/json' \
  -H 'X-Session-Id: demo-weather-001' -H 'X-User-Id: alice' \
  -d '{"q":"北京今天天气怎么样？"}'
```

See [`demo-springboot/README.md`](demo-springboot/README.md) for full
instructions (env vars, three verification scenarios, FAQ).

---

## 7. Coexisting with ARMS / other OTel implementations

The SDK **relocates all `io.opentelemetry.*` classes to
`com.tencentcloudapi.observability.shaded.io.opentelemetry.*`** at build time,
which means:

- If ARMS javaagent is attached to the JVM, its bytecode instrumentation
  pattern-matches on `io.opentelemetry.*` and **cannot** see our internal
  Tracer → our spans are never hijacked.
- The SDK's internal `OpenTelemetrySdk` is built with `build()`, not
  `buildAndRegisterGlobal()` → never fights the host app for the
  `GlobalOpenTelemetry` singleton.
- The only package we deliberately do **not** shade is
  `io.micrometer.observation.*` — the SDK must share this SPI contract
  with Spring AI to receive events.

---

## 8. Debugging

Enable `CLS_DEBUG=1` or `ClsConfig.builder().debug(true)` to log each
observation's start/stop at SLF4J debug level, useful for confirming events
are flowing through.

Common issues:
1. **No spans received** — check the handler is on the `ObservationRegistry`
   Spring AI actually uses, not a fresh one you created.
2. **No trace on CLS** — check endpoint / topic / secret; debug logs print
   the full URL.
3. **Empty prompt content** — Spring AI's `include-prompt` is off by default,
   so its native `gen_ai.prompt` is empty; this SDK's
   `gen_ai.input.messages` / `gen_ai.output.messages` fields always carry
   the content, prefer those.

---

## 9. Regression suite

A full end-to-end regression suite covering 12 verification cases
(top-level sync / stream, ReAct round-switch, ChatMemory multi-turn,
tool error swallow/rethrow, 3-layer nested subagents, 10-way concurrent
streaming, sequential sibling ChatClients, Reactor cross-thread
subagents) ships at [`scripts/run-regression.sh`](scripts/run-regression.sh).

```bash
./scripts/run-regression.sh
```

One command installs the SDK, launches demo-springboot, fires all 12
cases, parses the server log for trace structure, and prints a per-case
PASS/FAIL verdict table. See
[`docs/testing/regression-playbook.md`](docs/testing/regression-playbook.md)
for the full case catalogue, assertion rules, failure triage, and the
procedure for adding new cases.

---

## License

Apache License 2.0.
