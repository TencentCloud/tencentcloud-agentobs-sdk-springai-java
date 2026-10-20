# Fix: Top-level `.stream()` observation trace parenting (Reactor context propagation)

- **Date**: 2026-10-15
- **Status**: Approved (brainstorming done 2026-10-15)
- **Scope**: SDK bootstrap only — `ClsConfig` + `ClsObservability.install()`
- **Risk**: Low (opt-out available; idempotent JVM hook; reflection-guarded)

---

## 1. Problem

When a user calls `ChatClient.prompt().stream()` (or any `ChatModel.stream()` / `EmbeddingModel.stream()` /
retrieval-advisor streaming path) **from a thread that already owns an open parent `Observation`** (e.g. a
Spring MVC request thread, a user-defined subagent root span, a `@Scheduled` tick), the resulting
Spring AI inner Observations — chat / embedding / retrieval / inner-advisor / tool — come out of the
SDK with `parent = null` and therefore land in **separate root traces** on CLS.

### Root cause

Spring AI 1.x / 2.x streaming paths return `Flux<...>`. Reactor's own thread model switches work
onto its own scheduler threads (`parallel-*`, `boundedElastic-*`, HTTP client worker, …) between the
caller `subscribe()` and the actual callback where Spring AI opens its child observation.

Spring AI's child-observation parent lookup goes through **Micrometer's
`ObservationThreadLocalAccessor.KEY`** in the Reactor Context — but Reactor **does not** carry the
calling thread's `ThreadLocal` state across those scheduler jumps by default. The parent `Observation`
is stored in a `ThreadLocal` on the originating thread; the child-observation code runs on a worker
thread where that `ThreadLocal` is empty → `getCurrentObservation()` returns `null` → the child is
opened as a brand-new root → fragmented trace.

The sync path (`.call()`) is unaffected because it never leaves the caller thread.

### Why the SDK must fix it (and not the user)

The SDK is the thing that **promises** "install me and get coherent GenAI traces on CLS". A user
reasonably expects coherent parenting for both `.call()` and `.stream()`. Making every user manually
call `reactor.core.publisher.Hooks.enableAutomaticContextPropagation()` in their `main()` is the kind
of foot-gun that makes the SDK look broken on first-run.

---

## 2. Solution

At `ClsObservability.install()` time, enable Reactor's **automatic context propagation** hook, which
in turn allows Reactor to pick up any `ThreadLocalAccessor` registered via the ServiceLoader-driven
`io.micrometer.context.ContextRegistry` — including **`ObservationThreadLocalAccessor`**, which
`micrometer-observation.jar` registers via
`META-INF/services/io.micrometer.context.ThreadLocalAccessor` by default.

Once the hook is on:

1. Reactor captures `ObservationThreadLocalAccessor.getValue()` from the subscribing thread at
   `subscribe()` time (i.e. the parent `Observation` of the caller).
2. When Reactor hops to a worker thread, it restores that value into the worker's `ThreadLocal`
   before running user callbacks.
3. Spring AI's `getCurrentObservation()` → finds the parent → opens its child under it → parent
   chain is restored on CLS.

This is the exact mechanism Spring Boot's own Micrometer-Tracing autoconfig uses for the same
problem; we just do it ourselves because the user may be running without Spring Boot tracing.

### Hook properties (verified against micrometer-observation 1.13 – 1.17 jars in `~/.m2`)

| Property | Value |
|---|---|
| JVM-global | Yes (`Hooks.enableAutomaticContextPropagation()` is a static side-effect) |
| Idempotent | Yes — safe to call once, safe to call ten times |
| Service-loaded accessors | `ObservationThreadLocalAccessor` ships inside `micrometer-observation.jar` as a `META-INF/services` entry → always registered when micrometer-observation is on the classpath |
| OTel coupling | None — fix depends only on Micrometer's observation accessor, not on any OTel context accessor |

---

## 3. Design

### 3.1 `ClsConfig` — new field

Add one boolean config knob matching the existing `boolean debug` pattern.

| Dimension | Value |
|---|---|
| Field | `private final boolean reactorContextPropagation` |
| Default | `true` |
| Builder | `.reactorContextPropagation(boolean)` |
| Env var | `CLS_SPRINGAI_REACTOR_CONTEXT_PROPAGATION` (truthy → true; falsy → false) |
| System property | `cls.springai.reactor-context-propagation` (same semantics) |
| Resolution order | builder explicit > system property > env var > default `true` |

Rationale for the two names: existing SDK config supports both env and sysprop for most knobs; we
mirror that so users on Spring Boot's `spring-boot:run` (which forwards `-D` into the forked JVM)
and users on bare `java -jar` (which prefer env) both have a native switch.

### 3.2 `ClsObservability.install()` — new hook block

Insert the hook call **before** `OtlpHttpSpanExporter.builder()` runs, because:

- (a) It must run before Spring AI's `ChatClient` / `ChatModel` beans get a chance to execute
      `.stream()` on any application thread. The user calls `install()` at the top of their startup
      (typical pattern), so this covers them.
- (b) It has no dependency on the tracer provider or any OTel object — moving it earlier means a
      failure in the hook never risks poisoning the OTel bootstrap.

Reflection-based invocation (zero compile-time coupling to `reactor-core`):

```java
if (config.reactorContextPropagation()) {
    enableReactorContextPropagation();
}

// ...

private static void enableReactorContextPropagation() {
    try {
        Class<?> hooksClass = Class.forName("reactor.core.publisher.Hooks");
        hooksClass.getMethod("enableAutomaticContextPropagation").invoke(null);
        LOG.info("[cls-springai] enabled Reactor automatic context propagation " +
                "(restores parent trace linkage for ChatClient.stream() / " +
                "ChatModel.stream() / retrieval-advisor streaming)");
    } catch (ClassNotFoundException e) {
        // Reactor isn't on the classpath — user is likely on a pure sync Spring AI
        // integration. Nothing to do; the sync (.call()) path never had the problem.
        LOG.debug("[cls-springai] reactor-core not on classpath; skipping Reactor " +
                "context propagation hook (streaming trace parenting fix is a no-op here)");
    } catch (Throwable t) {
        // Any other failure (NoSuchMethodException on an unexpectedly old Reactor,
        // SecurityManager denial, etc.) must not break SDK install().
        LOG.warn("[cls-springai] failed to enable Reactor context propagation ({}); " +
                "top-level .stream() traces may be fragmented. " +
                "To silence, set CLS_SPRINGAI_REACTOR_CONTEXT_PROPAGATION=false.",
                t.toString());
    }
}
```

### 3.3 Shade safety

The reflection string `reactor.core.publisher.Hooks` is a **literal String constant**. The
maven-shade-plugin's class relocation only rewrites references in bytecode `CONSTANT_Class` entries
and does not scan `CONSTANT_String` entries. Therefore shading does not corrupt this call. (The SDK
already relies on this guarantee elsewhere — e.g. the `io.micrometer.observation.*` subscriptions
are via SPI not via reflected strings, but reflected strings for non-shaded packages like
`reactor.core` are intentionally safe.)

### 3.4 Interaction with existing SDK behavior

| Area | Impact |
|---|---|
| `ClsObservationHandler` | No change needed. Once Reactor propagates `ObservationThreadLocalAccessor` for us, Spring AI's own observation lifecycle produces correctly parented contexts; the handler sees them exactly like it already sees `.call()` events. |
| `.call()` sync path | No change (never crossed threads). |
| `.stream()` top-level path | **Fix applied**: child observations now land under caller's parent (e.g. subagent root, Spring MVC server span). |
| Subagent / tool-call / inner-advisor nested parenting | Still works; the Reactor hook does not alter the SDK's own `ThreadLocal`-based bookkeeping (`CHATCLIENT_FRAME`, `TOOL_FRAME`, `_FRAMES`). It only fixes the Spring-AI-side parent lookup for the top boundary. |
| Users running their own `Hooks.enableAutomaticContextPropagation()` | No-op (JVM hook is idempotent). |
| Pure WebMVC apps using only `.call()` | No visible effect; the hook enables a Reactor machinery they never trigger. |
| Reactor missing from classpath | Caught, debug-logged, SDK continues. |

### 3.5 Explicit non-goals

- We do **not** register any custom `ThreadLocalAccessor`. The one we need
  (`ObservationThreadLocalAccessor`) is service-loaded by `micrometer-observation.jar`.
- We do **not** shim OTel context propagation. The SDK uses an **isolated** OpenTelemetrySdk that
  is never registered globally, so cross-library OTel context compatibility is explicitly out of
  scope.
- We do **not** touch the Reactor Context chain inside the handler. If someone's custom code
  writes a key to Reactor Context, we don't read it; we only let Reactor itself move Micrometer's
  observation ThreadLocal across threads.

---

## 4. Verification plan

Re-run the subagent harness G1-plain and G5-replicas (previously the two failing cases where
streaming severed the subagent root parent). Expected:

- G1-plain `.stream()`: `invoke_agent → chat / tool / advisor` all share **one** trace id, with
  `invoke_agent` as the single root → ✅
- G5-replicas: concurrent streaming subagents each produce ONE coherent trace per subagent
  invocation; no leakage between parallel subagents → ✅
- G1-plain `.call()`: unchanged → ✅ (regression check)
- G3 soft/hard: unchanged → ✅ (regression check)
- G4 nested-subagent: unchanged → ✅ (regression check)

Record outcomes in `CHANGELOG.md` under the new Fix entry.

---

## 5. Rollout

Single patch version bump (`0.0.9` → `0.0.10`).

CHANGELOG entry sketch:

> ### Fixed
> - **Top-level `.stream()` traces now preserve parent linkage.** Previously, when the caller of
>   `ChatClient.stream()` / `ChatModel.stream()` / retrieval-advisor streaming already had an open
>   `Observation` on its thread (e.g. a Spring MVC request, a user-defined subagent root span,
>   a `@Scheduled` tick), Reactor's scheduler jumps caused Spring AI's inner observations to be
>   opened without a parent, producing fragmented traces on CLS. `ClsObservability.install()` now
>   enables Reactor's automatic context propagation hook, which lets Micrometer's
>   `ObservationThreadLocalAccessor` cross Reactor threads. Opt out with
>   `.reactorContextPropagation(false)` on `ClsConfig.Builder`, env
>   `CLS_SPRINGAI_REACTOR_CONTEXT_PROPAGATION=false`, or sysprop
>   `-Dcls.springai.reactor-context-propagation=false`. Sync `.call()` paths are unchanged.
