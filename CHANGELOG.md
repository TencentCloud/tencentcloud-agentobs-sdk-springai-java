# Changelog

All notable changes to `tencentcloud-agentobs-sdk-springai-java` are documented
in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

---

## [1.0.1] - 2026-10-21

### Added

- **Head-based trace sampling** via `CLS_SAMPLE_RATIO` /
  `-Dcls.springai.sample-ratio` / `ClsConfig.Builder#sampleRatio(Double)`,
  defaulting to `1.0` (keep every trace, i.e. the pre-existing behaviour).
  Resolution order is builder → system property → env var → `.env` →
  default. Values outside `[0.0, 1.0]` are clamped; `NaN` and unparseable
  values fall back to `1.0`, on the grounds that silently dropping
  telemetry is a worse failure mode than over-reporting.
  - `SdkTracerProvider` is now built with an explicit sampler:
    `alwaysOn()` at ratio `1.0`, `alwaysOff()` at `0.0` (logged as a
    `WARN`, since nothing will reach CLS), and
    `parentBased(traceIdRatioBased(ratio))` in between.
  - **A turn is sampled whole or not at all.** Only `enter_application`
    is a trace root; every other span the SDK emits (agent / react /
    chat / tool / embedding / retrieval, plus any nested
    `invoke_subagent` subtree) is created with an explicit parent, so the
    `ParentBased` wrapper makes it inherit the root's verdict rather than
    re-roll. CLS never renders a partially-sampled trace tree.
  - Because `traceIdRatioBased` keys off the trace id and each top-level
    `ChatClient` call starts a fresh trace, the ratio applies **per turn,
    not per session** — a long conversation will show gaps between turns.
    Keep `1.0` where per-session auditability matters.
  - Sampling reduces upload volume and CLS storage, not in-process cost:
    sampled-out turns still traverse the handler's state machine.
  - The effective ratio is reported in the existing `install()` info log
    (`installed (service=..., endpoint=..., topic=..., sampleRatio=...)`).
- Unit test `ClsConfigSampleRatioTest` pinning the builder / system-property
  resolution order, clamping and fallback behaviour.

### Compatibility

- No API removal; `sampleRatio` defaults to `1.0`, so existing deployments
  behave exactly as in `1.0.0` unless they opt in.
- The handler's turn/scope state machine was not touched. It reads only
  `SpanContext` (which stays valid for non-recording spans) and has no
  `isRecording()` branches, so sampled-out turns keep their parent/child
  wiring intact.

### Build

- `pom.xml` version bumped `1.0.0 → 1.0.1`; the SDK dependency in
  `demo/pom.xml` and `demo-springboot/pom.xml` bumped to match. The
  `springai.version` properties are unrelated and left untouched.

---

## [1.0.0] - 2026-10-16

First formal GA release. Consolidates the sequential-stream memory-leak
fix, an internal refactor of the observation handler, trace-tree naming
improvements aligned with CLS GenAI Trace spec v5, and a streaming-call
marker. All changes are backward compatible — no public API or
configuration change.

### Added

- **Trace-tree span names now carry their key identifier**, matching the
  OpenTelemetry GenAI `"{operation} {detail}"` naming convention so the CLS
  console tree is self-descriptive instead of showing bare operation names:
  - `chat` → `chat <model>` (per spec §3.3; model resolved from the
    `gen_ai.request.model` tag, falling back to the request Prompt's
    `ChatOptions.getModel()`)
  - `invoke_agent` → `invoke_agent main`
  - `invoke_subagent` → `invoke_subagent <agentName>` (e.g. the triggering
    tool name, or `subagent-N` fallback)
  - `execute_tool` → `execute_tool <toolName>`
  - `retrieval` → `retrieval <retriever_name>` (per spec §3.7; name resolved
    from the vector-store/db provider tag, falling back to the
    Observation.Context class simple name sans "ObservationContext")
  - `react` → `react round_<N>` (per CLS GenAI Trace spec §3.3; applies to
    both the native round-1 react and the SDK-synthesised round-2+ reacts)
- **`gen_ai.tool.name` attribute** is now stamped on every `execute_tool`
  span (previously the tool name was only used internally for subagent
  detection and never surfaced on the span itself).
- **`gen_ai.request.streaming` attribute** (SDK extension; not in spec v5)
  marks whether a turn is a streaming call (`.stream()`) vs a blocking one
  (`.call()`). Read once from Spring AI's `ChatClientObservationContext.isStream()`
  at turn start and stamped on the `entry`, `agent`/`invoke_subagent`, and
  every `chat` span (chat spans inherit it from their `TurnState`). Only
  written when `true`, so blocking calls carry no attribute.

### Fixed

- **Memory leak under sequential `ChatClient.stream()` calls.** A turn's
  `onStop` runs on a Reactor worker where `Scope.close()` is a no-op on the
  originating thread, leaving a residual OTel Context whose `CLS_TURN_KEY`
  points back at the finished `TurnState`. Each new turn's Scope recorded
  that residue as `beforeAttach`, chaining successive `TurnState`s (and their
  Spans / `ChatResponse`) together so none could be collected — heap grew
  linearly with the number of turns.
  - Added `TurnState#release()` to null every retaining reference
    (`entryScope`/`agentScope`/`ctxScope`/`activeReactScope`/`entrySpan`/
    `agentSpan`/`activeReactSpan`/`lastChatResponse`/`outer`) and clear
    `stepIdStack`; made those fields (and `outer`) non-final/`volatile`.
  - `release()` is called at the end of `onStop` for both root and subagent
    turns, after `outer` is consumed; the entry scope/span close is now
    null-guarded.
  - Corrected the stale "residue is bounded by thread-pool size" comment in
    `startRootTurn`.
  - **Verified:** real-LLM stress on `/sequential-stream-demo` (80 turns)
    keeps live `TurnState`/`SdkSpan`/`ScopeImpl` counts flat (5 / 8 / 0)
    across full-GC class histograms, versus linear growth before the fix.

### Changed (internal, no behavioral impact)

- **Unified swallowed telemetry-path exceptions** through a single
  `swallow(site, throwable)` helper that logs at `DEBUG` (parameterized, so
  zero cost when debug is off). Previously these were a mix of `LOG.warn` and
  silent `catch (Throwable ignored) {}`, which either spammed host logs or
  hid failures entirely. Expected control-flow branches (reflection getter
  probing, feature detection) intentionally still do not log.
- **Extracted the stateless reflection/text helpers** out of
  `ClsObservationHandler` into a new package-private `ClsReflect` class
  (`invokeRequest`/`invokeResponse`/`invokeFirst`/`invokeString`/
  `findAccessor`/`findTagValue`/`parentContext`/`truncate` plus the accessor
  caches). The handler shrank by ~90 lines and now focuses on the
  span/turn lifecycle state machine. The turn/scope/subagent/ReAct state
  machine was deliberately left in place.

### Build

- `pom.xml` and `demo-springboot/pom.xml` version bumped `0.0.13 → 1.0.0`.

---

## [0.0.13] - 2026-10-16

### Investigation summary

- **Deep-dive brainstorm on the "slow memory growth under sequential
  `.stream()` workload" symptom reported after 0.0.12 shipped.** Over
  five diagnostic rounds we traced the growth to residual OTel scopes
  left on the Tomcat worker's `ArrayBasedContextStorage` LIFO stack
  when `onStop` runs on a Reactor `boundedElastic` worker and
  therefore cannot `.close()` scopes rooted on the Tomcat worker.
  Each leaked scope keeps a `TurnState` (~1.8 KB) reachable until the
  stack frame holding it is finally popped on a later request.

### Attempted and rolled back

- **"Reclaim residue in gate c"** approach (prototype commit
  `18304a1`): on gate-c trigger, close the residue's
  `ctxScope / agentScope / entryScope` in LIFO order on the Tomcat
  worker that originally pushed them. The design relied on OTel's
  silent no-op semantics for off-top-of-stack closes (verified safe
  by the earlier G6 scope-LIFO diagnostic). Instrumented with a
  `DIAG-RECLAIM` probe (prototype commit `908a816`) and validated
  with G11 (4 sequential top-level streams on one Tomcat worker,
  session `v5-diag`).

  **Empirical outcome: 3 / 3 reclaim attempts observed
  `top_matches=false`.** The current stack top at gate-c entry was
  **not** our `ctxScope` but **Spring AI's own `chat` observation
  Scope** (`SdkSpan{name=chat, kind=CLIENT, gen_ai.turn.id=<prior
  turn>}`). Because Spring AI itself calls `.makeCurrent()` on the
  `chat` observation inside the Reactor pipeline and its `onStop`
  also runs on a Reactor worker, that scope is leaked **above** our
  three scopes. OTel's LIFO contract means our three closes silently
  no-op in this configuration — the reclaim provides no measurable
  benefit while adding complexity.

  Rolled back the reclaim path and the DIAG probe in commits
  `5647763` and `77e2ef7`. The gate-c branch now simply rebases
  `parentCtx` to `Context.root()` (as in 0.0.12) with an inline
  comment documenting why no reclaim is attempted.

### Known limitation (upstream)

- **OTel scope stack leakage under `ChatClient.stream()` is a joint
  property of Spring AI's `.chat` observation lifecycle and Reactor's
  cross-thread signal delivery, not a CLS-SDK-only issue.** Spring
  AI's `ObservationHandler.onStart` makes the `chat` observation
  current on the subscribing thread (typically Tomcat worker) and
  its `onStop` runs on a Reactor `boundedElastic` worker; the Scope
  object held by Micrometer's `OtelObservationHandler` is closed
  on the Reactor worker, which cannot pop the entry from the Tomcat
  worker's OTel stack. CLS SDK's scopes sit **underneath** that
  Spring AI `chat` scope on the same stack and share the same fate.

  **Impact bound**: residue is capped by the Tomcat/worker
  thread-pool size (not per-request). Worst-case steady-state is
  `~1.8 KB × worker_count × (CLS_scope_depth + Spring_AI_chat_depth)`
  —  typically a few hundred KB for a default Tomcat pool
  (`max-threads=200`), not a growing leak. Correctness (trace
  independence, subagent classification) is unaffected: gate 1-A
  and gate c continue to protect them.

  **Workaround options for memory-sensitive deployments**:
  1. Cap `server.tomcat.threads.max` to the smallest acceptable value
     — residue is bounded by this count.
  2. Prefer non-`.stream()` ChatClient calls where feasible — the
     synchronous path keeps `onStop` on the Tomcat worker and the
     scope stack unwinds normally.
  3. Periodic Pod rotation if the SpringAI version in use exhibits
     the pattern (verified for upstream Spring AI versions used by
     this release).

  **Upstream direction**: the empirical finding (Spring AI `chat`
  Scope leaked above CLS scopes on the Tomcat stack under `.stream()`)
  will be reported to Spring AI upstream so that `ObservationHandler
  .onStop` can honor the originating thread for the scope close.
  No SDK-side fix in `ClsObservationHandler` is viable without
  reflecting into Spring AI internals, which this release
  deliberately does not do.

### Chore

- `pom.xml` and `demo-springboot/pom.xml` version bumped
  `0.0.12 → 0.0.13`.
- No behavioral change in production code paths relative to 0.0.12.
  Gate 1-A and gate c from 0.0.12 are preserved verbatim.

---

## [0.0.12] - 2026-10-16

### Fixed

- **Cross-stream trace pollution within a single HTTP request now fully
  resolved on both the SDK layer and the OTel span-tree layer.** When a
  controller executes multiple top-level `ChatClient.stream().blockLast()`
  calls sequentially on the same Tomcat worker thread (e.g. four streams
  chained to answer four prompts in one request), the previous
  behavior exhibited two symptoms:

  1. **SDK layer**: streams 2 through N were misclassified as
     `invoke_subagent` of stream 1 instead of independent root turns,
     because `onStop` runs on a Reactor `boundedElastic` worker and
     cannot clean the Tomcat worker's OTel scope stack or
     `CURRENT_TURN` ThreadLocal — channels ③/④ in
     `startChatClientTurn` kept returning the residual TurnState from
     stream 1.
  2. **OTel span-tree layer** (visible only on the CLS platform): even
     after the SDK classification was corrected, `startRootTurn`'s
     `.setParent(Context.current())` still read the residual OTel
     scope and inherited the previous stream's `traceId`, nesting all
     N `enter_application` spans into a single 16-span giant trace.

  **Fix: dual-gate design using `CLS_TURN_KEY != null` as the shared
  residue signal**:

  - **Gate 1-A** (`startChatClientTurn`): channels ③ (OTel Context)
    and ④ (ThreadLocal) are only consulted when
    `context.getParentObservation() != null`. Fresh top-level
    ChatClients have no parent observation; legitimate same-thread
    descendants (EmbeddingModel in RAG, tool-call restored
    observations) always do. The gate zero-ambiguously discriminates
    residue from legitimate reads.
  - **Gate c** (`startRootTurn`): when
    `Context.current().get(CLS_TURN_KEY) != null`, swap the parent
    context to `Context.root()` before creating the
    `enter_application` span. The new root turn gets a fresh traceId
    independent of any stale scope on the thread.

  Both gates are additive (no field or method removed) and use the
  same residue signal. The design was validated empirically against
  the CLS GenAI Trace platform with the following session traces
  (SDK version 0.0.12, Hunyuan hy4-preview):

  | Scenario | Expected | Verified |
  |---|---|---|
  | G11 (4 sequential top-level streams, same Tomcat worker) | 4 independent traceIds, 0 subagents | ✅ 4 traceIds: `bE0d07Da`/`aaEf4f68`/`02B9c79C`/`1A322c41` |
  | G8 (outer call + inner call via tool) | 1 trace, 1 subagent | ✅ `inspectService` subagent linked to outer |
  | G10 (outer stream + inner stream, cross-Reactor-worker) | 1 trace, 1 subagent | ✅ `inspectServiceStream` subagent linked to outer (even on `boundedElastic-N`) |

  See `docs/plans/2026-10-16-stream-cross-request-leak-dual-gate-fix-design.md`
  for full root-cause analysis and the four-round brainstorming history
  (three rolled-back attempts before arriving at the dual-gate design).

### Known trade-off

- When a Tomcat worker has both a business parent context (e.g.
  injected by an APM javaagent like Alibaba ARMS) **and** a residual
  CLS scope from a prior stream on the same worker, gate c switches
  to `Context.root()` to clear the residue — losing the APM linkage
  for that specific request. OTel Context is immutable-layered and
  cannot be surgically edited to keep one layer while removing
  another. Core CLS trace independence takes priority over
  best-effort APM stitching, which may already be partially broken
  under high-concurrency stream workloads. Layered-context-aware
  sanitization is deferred to a future release.

### Chore

- `demo-springboot/pom.xml` SDK dependency bumped `0.0.11 → 0.0.12`.
  Same hygiene as the `0.0.10 → 0.0.11` bump.

---

## [0.0.11] - 2026-10-16

### Fixed

- **Sequential top-level `ChatClient` turns on the same thread now produce
  independent traces.** Previously, when a single HTTP request issued two
  consecutive `ChatClient.prompt(...).call()` invocations on the same
  thread (e.g. the `MultiAgentDemoController` pattern: translator-then-
  summarizer, "G6"), the second turn's `enter_application` span was
  attached as a child of the first turn's `invoke_agent` span, producing
  a single nested trace instead of two sibling traces on CLS.

  Root cause: `ClsObservationHandler` opened three OTel scopes per root
  turn (`entryScope → agentScope → ctxScope`) but closed them in the wrong
  order (`agentScope → entryScope → ctxScope`), violating LIFO. OTel's
  `ArrayBasedContextStorage.Scope.close()` **silently no-ops** when the
  scope being closed is not the current top of the stack, so the first
  two closes were effectively ignored and both the agent scope and the
  entry scope leaked onto the thread. The second turn's `Span.current()`
  read the leaked agent span and used it as parent.

  Fix is three localised edits in `ClsObservationHandler.onStop(...)`:

  1. The generic span-close block now skips `isTurn` observations
     (`if (!s.isTurn) { s.scope.close(); s.span.end(); }`). Turn
     observations get their scope+span lifecycle managed exclusively
     by the per-turn teardown branches below.
  2. The root-turn teardown branch closes in strict LIFO:
     `ctxScope (C) → agentScope (B) → entryScope (A)`, ending each span
     immediately after its scope's close.
  3. The subagent-turn teardown branch (which had the analogous latent
     LIFO violation `agentScope → ctxScope`) now closes in strict LIFO:
     `ctxScope → agentScope`. Subagents have no `entryScope` (they
     share the outer turn's entry).

  Error handling: the four scope-close call sites previously used
  `catch (Throwable ignored) { /* noop */ }`, which is exactly what hid
  this defect for weeks. They now log at `WARN` with the exception's
  short description. Normal operation after the fix produces zero such
  warnings; a non-zero count indicates a new scope-management bug
  surfacing immediately rather than silently.

### Verified post-fix (hy4-preview, one server run)

Diagnostic probes placed in the root teardown branch (removed in a
follow-up commit) confirmed clean LIFO unwinding:

```
root-stop TURN#1 AFTER ctxScope.close:   spanId=<agent1>  (ctx popped, agent on top)
root-stop TURN#1 AFTER agentScope.close: spanId=<entry1>  (agent popped, entry on top)
root-stop TURN#1 AFTER entryScope.close: spanId=0000..    (entry popped, root reached)
root-start TURN#2 BEFORE anything:       spanId=0000..    (clean stack)
```

Regression suite re-executed against the rebuilt `0.0.11` artifact
(the first run against a verifiably correct jar — prior "0.0.10"
regressions were accidentally hitting a stale `0.0.9` artifact in
`~/.m2` because `demo-springboot/pom.xml` had not been bumped):

| ID | Scenario | Trace result |
|---|---|---|
| G6 (primary) | Two sibling ChatClients, same thread | ✅ Two independent trace ids (`dcB8E8DF...` / `BAaCEcCb...`) |
| G1-plain | Top-level `.stream()`, plain LLM | ✅ 16 spans share one trace |
| G1-rich | Top-level `.stream()` + RAG + tool | ✅ 23 spans share one trace |
| G2 | ChatMemory two-turn recall | ✅ Second turn recalls "小明" |
| G3-soft | Tool soft-swallowed exception | ✅ Response synthesised, trace intact |
| G3-hard | Tool exception rethrown | ✅ `ToolExecutionException` surfaces cleanly |
| G4 | 3-layer nested subagents | ✅ Full inspection report, 170+ spans share one trace |
| G5 | 10-concurrent `.stream()` × 2 sessions | ✅ 10/10 HTTP 200, per-session counters correct |
| G7 | Non-nested `.call()` + tool | ✅ 26 spans share one trace |

### Supplementary subagent regression (added after initial G6 verification)

The initial pass covered subagent only via G4 (3-layer direct-chain
nesting). After the fix we audited remaining demo endpoints and found
three `SubAgentDemoController` paths that exercise the **single-layer
subagent** teardown — the exact code branch where the LIFO fix moved
`agentScope.close` + `agentSpan.end` out of the generic span-close
block and into the subagent branch itself. These three paths also
exercise **Reactor cross-thread scheduling** (outer `.stream()`
hops onto a different `nio-exec-N` worker before Spring AI opens the
subagent observation). All three paths pass cleanly:

| ID | Scenario | Endpoint | Threads observed | Trace result |
|---|---|---|---|---|
| G8 | outer `.call()` + inner `.call()` | `/subagent-demo` | `nio-exec-1` (single) | ✅ 7 spans / 1 trace |
| G9 | outer `.stream()` + inner `.call()` | `/subagent-demo/stream` | `nio-exec-1` + `nio-exec-3` | ✅ 7 spans / 1 trace |
| G10 | outer `.stream()` + inner `.stream()` | `/subagent-demo/stream-deep` | `nio-exec-1` + `nio-exec-6` | ✅ 7 spans / 1 trace |

Every trace carries the CLS v5 shape (1 TURN + 3 chat + 2 advisor + 1 tool
= 7 spans), and the scope-close WARN counter stayed at **0** across all
three runs — i.e. the new LIFO close path (`ctxScope → agentScope`) in
the subagent branch is exercised to completion with no silent failures,
even when the subagent is opened on a thread different from where the
root turn started.

### Behavioural notes

- No API change.
- No new config knob (fix corrects a defect; no legitimate use case
  for opting into the broken behaviour).
- The `0.0.10` behavioural note about sequential sibling ChatClients
  sharing one request-level trace is now moot — they produce
  independent traces as users originally expected.

### Chore

- `demo-springboot/pom.xml` now pins SDK to `0.0.11` (previously
  `0.0.10`). The earlier `0.0.9 → 0.0.10` bump missed this file, so
  running the demo validated the previous SDK version instead of the
  new one. This bump keeps the demo honest.

---

## [0.0.10] - 2026-10-15

### Fixed

- **Top-level `.stream()` traces are no longer fragmented across Reactor
  worker threads.** Before 0.0.10, calling `ChatClient.stream()` /
  `ChatModel.stream()` / a retrieval advisor's streaming path from a
  thread that was going to hop onto Reactor's `BoundedElastic` scheduler
  produced one new OTel trace per child observation — the TURN root span
  on `nio-exec-N` lived in trace A, the advisor in trace A, but every
  `gen_ai.client.operation` / `db.vector.client.operation` / `chat` span
  on `boundedElastic-N` became its own trace root B/C/D/…. Observers on
  CLS saw an orphan `invoke_agent` root with its own trace id and 3–5
  disconnected single-span traces for the actual LLM calls.

  Two independent changes together fix this:

  1. **Reactor automatic context propagation is enabled at
     `ClsObservability.install()` time.** The SDK reflectively invokes
     `reactor.core.publisher.Hooks.enableAutomaticContextPropagation()`
     during install (zero compile-time dependency on `reactor-core`). This
     makes Reactor carry Micrometer's `ObservationThreadLocalAccessor`
     across scheduler threads so Spring AI's inner observations correctly
     see their parent `Observation` on `boundedElastic-N`. Opt out via
     `.reactorContextPropagation(false)` on `ClsConfig.Builder`, env
     `CLS_SPRINGAI_REACTOR_CONTEXT_PROPAGATION=false`, or system property
     `-Dcls.springai.reactor-context-propagation=false`. The hook is JVM-
     global and idempotent, so coexisting with a user who already enables
     it elsewhere is harmless.

  2. **The handler resolves the parent OTel `Span` from the Micrometer
     parent-observation chain, not from OTel's thread-local.** OTel's
     `io.opentelemetry.context.Context` has no equivalent
     `ThreadLocalAccessor` registered, so `Span.current()` on a Reactor
     worker returns `INVALID` even when Micrometer parenting is correct.
     The SDK now walks `Observation.Context#getParentObservation()`
     upwards, reads the first ancestor's `SpanState` (which the handler
     attaches to every observation it processes), and uses that
     ancestor's `Span` as the explicit parent on `SpanBuilder.setParent`.
     Non-reactive paths and TURN / SUBAGENT identification (`invoke_agent`
     / `invoke_subagent`) are unchanged.

  End-to-end validation (hy4-preview, one server run):

  - **Top-level `.stream()` + RAG** (`/chat/stream-plain`): all 6 spans
    (`invoke_agent`, 1 outer advisor, 1 streamed `gen_ai.client.operation`,
    1 retriever, 1 inner `gen_ai.client.operation`, 1 final `chat`) now
    share one trace id across `nio-exec-1` → `boundedElastic-1`.
  - **3-layer nested subagents** (`/subagent-deep-demo`, previously G4):
    11 spans across 1 `TURN` + 2 `SUBAGENT` (one re-entered via multi-
    round ReAct) + 2 tools + 3 chats + 3 advisors all share one trace id,
    with correct per-layer `parent_tool_call.id` and strict LIFO
    start/stop. No regression.

### Behavioural note

Sequential sibling `ChatClient` calls inside one HTTP request (e.g.
G6 — translator then summarizer on the same Tomcat NIO thread) now
share a single trace id, where previously each call started a fresh
trace. The two sibling `TURN`s still carry independent per-session
turn ids (`:t1`, `:t2`) and open their own `invoke_agent` / `enter_
application` spans; only the trace id is unified. This is a side
effect of enabling Reactor automatic context propagation at JVM scope:
the OTel scope established by the first `TURN` becomes visible to the
second `TURN`'s `Span.current()` lookup, so the second `TURN`
re-uses the first trace as the request-level root. The net effect is
that one user request produces one coherent trace in CLS — users
typically consider this a correctness improvement, not a regression.
If you need the pre-0.0.10 semantics (one trace per sibling call),
set `.reactorContextPropagation(false)`.

### Verified regression scenarios (hy4-preview, 0.0.10)

| ID | Scenario | Result |
|---|---|---|
| G1-plain | Top-level `.stream()` plain LLM | ✅ 6 spans share 1 trace |
| G1-rich | Top-level `.stream()` + RAG + tool | ✅ 17 spans share 1 trace |
| G2 | ChatMemory multi-turn | ✅ 2nd turn correctly recalls prior |
| G3-soft | Tool throws, default processor swallows | ✅ Multi-span 1-trace, no errors |
| G3-hard | Tool throws, custom processor rethrows | ✅ 4 spans share 1 trace; exception bubbles cleanly |
| G4 | 3-layer nested subagents | ✅ 11 spans share 1 trace |
| G5 | 10 concurrent `.stream()` requests (2 sessions × 5) | ✅ 10/10 HTTP 200, per-session counter correct |
| G6 | Two sibling ChatClients sequentially in one request | ✅ 2 TURN counters (`t1`/`t2`), unified request-level trace (see Behavioural note) |
| G7 | Non-nested `.call()` + tool | ✅ 17 spans share 1 trace |

### Compatibility

- No user API changes beyond the new `ClsConfig.Builder#reactorContextPropagation`
  opt-out knob. Default is on.
- No new runtime dependency added. `reactor-core` resolution is
  reflective; absent at runtime it logs at `DEBUG` and continues.

---

## [0.0.9] - 2026-10-15

### Added

- **Sub-agent support (CLS GenAI Trace v5 `invoke_subagent`)**. Nested
  `ChatClient` calls — i.e. a tool handler that internally invokes another
  `ChatClient` — are now recognised as a sub-agent of the enclosing turn
  instead of starting a new root trace. The nested `ChatClient` emits an
  `invoke_subagent` span (`gen_ai.span.kind=agent`,
  `gen_ai.agent.scope=subagent`), re-uses the outer `session.id` / `turn.id`,
  and links back to the triggering tool via
  `gen_ai.subagent.parent_tool_call.id`. See
  [docs/plans/2026-10-14-subagent-support-design.md](docs/plans/2026-10-14-subagent-support-design.md).

### Fixed

- **Sub-agent detection under reactive streams**. `ChatClient.stream()`
  dispatches internal observations onto Reactor `BoundedElastic` worker
  threads where OTel `Context` and `ThreadLocal` cannot see the outer
  turn. Detection now walks four channels in priority order so every
  outer/inner combination resolves to the same trace:
  1. Micrometer `Observation` parent chain (synchronous + `outer=stream + inner=call`)
  2. Spring AI `ToolCallReactiveContextHolder` Reactor Context (double-reactive)
  3. OTel `Context` key `cls.turn-state` (ExecutorService wrap)
  4. `ThreadLocal` (same-thread fallback)

- **Sub-agent `agent.name` and `parent_tool_call.id` under double-reactive**.
  When both `outer` and `inner` use `.stream()`, the tool observation does
  not enter the Reactor Context — but empirically the tool handler and the
  inner `ChatClient.onStart` run on the same Reactor worker thread. Tool
  observation now publishes a `cls.tool-info` key on the current OTel
  `Context` for the duration of the tool scope, so the nested subagent
  reads real tool `name` + `call.id` instead of degrading to `subagent-N`
  / `null`.

### Compatibility

- Zero user API change. Pure additions to the observation handler.
- Non-nested and fully-synchronous nested behaviour is bit-identical to `0.0.8`.
- Uses Spring AI's `internal` package `ToolCallReactiveContextHolder` via
  reflection; any upstream rename degrades gracefully to the next
  detection channel.

### Verified scenarios (hy4-preview end-to-end)

| Scenario | Trace structure | `agent.name` | `parent_tool_call.id` |
|---|---|---|---|
| Synchronous `.call()` nesting | 1 root + N subagent | tool name | ✅ |
| `outer=.stream()` + `inner=.call()` | 1 root + N subagent | tool name | ✅ |
| `outer=.stream()` + `inner=.stream()` | 1 root + N subagent | tool name | ✅ |

### Additional end-to-end validation (post-release coverage expansion)

Beyond sub-agent scenarios, the following production-shape flows were
executed against hy4-preview to confirm the 0.0.9 handler changes do
not regress non-nested behaviour and that previously-untested branches
of the SDK are correct:

| ID | Scenario | Result |
|---|---|---|
| G7 | Non-nested `.call()` + tool regression | ✅ Tool span lifecycle bit-identical to 0.0.8 (added `CLS_TOOL_INFO_KEY` scope is transparent for non-nested flows) |
| G1-plain | Top-level `.stream()`, plain LLM, no RAG / tool | ✅ 1 turn, 2 chat spans, `stream=true` stamped correctly |
| G1-rich | Top-level `.stream()` + RAG + tool, full ReAct 2-round | ✅ Round switching + RAG retrieval spans + tool spans all correct |
| G2 | Multi-turn `ChatMemory` across HTTP requests | ✅ Per-session `turn.id` auto-increments (`t1 → t2 → ...`); memory advisor functionally recalls prior turn content |
| G6 | Two sibling ChatClients called sequentially in one HTTP request | ✅ Second call opens a fresh TURN (`t2`) on the same NIO thread as the first — confirms SDK's `onStop` clears the `CURRENT_TURN` ThreadLocal correctly between sibling calls, so per-session counter continues to increment across ChatClient instances within one request |
| G5 | 10 concurrent requests, 2 sessions × 5 requests each | ✅ Each session yielded exactly `{t1,t2,t3,t4,t5}` as a set — no duplicates, no gaps. Counters stayed independent per session (confirming `ConcurrentHashMap<AtomicInteger>` thread-safety). Tomcat NIO thread reuse across sessions did not leak `TurnState` (same thread handled different sessions, each starting from the correct `t1`). All 10 TURN `onStart` events landed within the same millisecond — true parallelism, not sequential queueing. |
| G4 | Three-layer nested subagents (direct chain) | ✅ 1 TURN + 3 SUBAGENT spans (incl. one layer re-entered twice via LLM's autonomous multi-round ReAct); all `parent_tool_call.id` distinct; `session.id` / `turn.id` shared across the whole tree; stack push/pop strict LIFO; zero errors |
| G3-soft | Tool throws + default `ToolExecutionExceptionProcessor` swallows | ✅ Zero `onError` invocations (correct — Micrometer sees no error); trace completes end-to-end with all spans OK |
| G3-hard | Tool throws + custom processor rethrows | ✅ Three `onError` invocations: tool span → advisor/step span → ChatClient (agent + entry) span. Entry and agent spans both marked ERROR; tool span additionally stamps `gen_ai.tool.error.type=execution_error`. No `chat round2` emitted (correct — exception terminates the chain). |

G3 confirms that SDK `onError` handles both tool-error semantics
Spring AI exposes:

- **Soft** = `DefaultToolExecutionExceptionProcessor` behaviour. The
  tool's exception is caught by Spring AI and returned to the LLM as a
  text payload; from Micrometer's point of view nothing errored, so
  SDK `onError` is correctly never called. Trace shows a normal
  2-round ReAct with the "error description" as the tool's output.
- **Hard** = a custom processor that rethrows (e.g. for fatal failures
  the host application wants to surface). The exception bubbles
  through Spring AI's observation chain; SDK's `onError` fires on
  every ancestor observation, each `recordException`-ing and setting
  `StatusCode.ERROR`. The entry span (CLS trace root) is also
  cascaded, so the entire trace is flagged ERROR in CLS UI without
  obscuring per-span granularity.

G4 confirms that the sub-agent `TurnState` stack handles arbitrary
nesting depth and multi-call-per-layer correctly. The LLM
autonomously executed a 2-round ReAct inside the mid layer (`inner`),
invoking the deepest tool twice; each invocation produced a distinct
grand-child sub-agent with its own `parent_tool_call.id`, cleanly
scoped under the right parent. Start/stop lifecycle events across all
7 observations (1 TURN + 3 SUBAGENT start/stop pairs) obey strict LIFO
with no leftover state after the request completes.

G5 and G6 together exercise the SDK under concurrency and non-nested
multi-agent scenarios where ThreadLocal lifecycle is the main
correctness risk:

- **G5** fires 10 concurrent requests across two sessions. All 10
  TURN starts land in the same millisecond on 10 different Tomcat
  NIO threads — proving true parallelism rather than queueing. Per
  `session.id`, the five requests received `{t1,t2,t3,t4,t5}` as a
  complete, duplicate-free set, confirming
  `ConcurrentHashMap<AtomicInteger>` is race-free. The two sessions'
  counters stayed independent (no accidental merging).
- **G6** calls two sibling ChatClients sequentially within one HTTP
  request. The second call opens a fresh TURN (`t2`) rather than
  reusing `t1`, which would only happen if the SDK forgot to clear
  `CURRENT_TURN` after the first agent span's `onStop`. The real
  prize comes when combining the two: a single NIO thread
  (`nio-8080-exec-1`) handled G6 first (opening `g6-multi:t1` and
  `t2`), then handled a G5 request a few seconds later and correctly
  returned `g5-B:t1` — zero cross-request contamination through
  ThreadLocal.

---

## [0.0.8] - 2026-10-14 (superseded by 0.0.9)

Initial sub-agent support (`invoke_subagent`). Shipped the feature but
had three reactive-path gaps that are fixed in `0.0.9`:

- Only ThreadLocal + OTel Context detection, which fails under any
  reactive stream.
- Double-reactive (`outer=.stream()` + `inner=.stream()`) produced two
  parallel root traces instead of one.
- Even after a mid-release fix, double-reactive subagent `name` and
  `parent_tool_call.id` degraded to placeholders.

Users on `0.0.8` should upgrade to `0.0.9` to pick up the complete
reactive support. There is no API or configuration change required.

---

## [0.0.7] - earlier

### Added

- P2 — `resource.*` attributes (service identity) and `gen_ai.user.name`
  propagation from `ClsBaggage`.
- P3 — custom span attributes via `ClsBaggage.attr(key, value)`.
- Per-session turn counter: `gen_ai.turn.id` now resets per `session.id`
  rather than being process-global.

---

## [0.0.5] - earlier

### Added

- P1 — `retriever` span kind for RAG document retrieval observations.
- `duration_ms` attribute on leaf spans (chat / tool / retrieval).

---

## [0.0.4] - earlier

### Changed

- Aligned span attribute names and shapes with the CLS GenAI Trace v5
  specification (P0 fixes).

---

## [0.0.3] - earlier

### Added

- Spring AI 2.0 compatibility:
  - Advisor chain collapsing (nested advisor observations fold into the
    enclosing `react` step).
  - Embedding span kind correction.
  - Per-round `react` emission for multi-round ReAct cycles.

---

## [0.0.1] - earlier

Initial release.
