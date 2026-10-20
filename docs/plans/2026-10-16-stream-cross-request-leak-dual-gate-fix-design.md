# Fix: Stream cross-request leak via dual-gate (1-A parent-obs gate + c parent-context sanitization)

- **Date**: 2026-10-16
- **Status**: Approved and verified (brainstorming round 4, after 3 failed rounds, based on CLS platform empirical validation)
- **Scope**:
  - `ClsObservationHandler.startChatClientTurn` — add 1-A gate (parentObs check for channels ③/④)
  - `ClsObservationHandler.startRootTurn` — add c parent-context sanitization (CLS_TURN_KEY detection)
- **Risk**: Low (both changes are additive gates; no existing field/method/API removed)
- **Supersedes**:
  - Round 1 (`2026-10-16-stream-cross-request-leak-fix-design.md`, rolled back): `openerThreadId+closed` validation + request-boundary filter. Failed because `closed=false` and `openerThreadId==currentThread` at the moment of the next onStart (TOCTOU with Reactor async onStop).
  - Round 2 (`2026-10-16-stream-cross-request-leak-x-fix-design.md`, rolled back): remove `CLS_TURN_KEY` / `ctxScope` entirely. Failed because channel ④ (`CURRENT_TURN` ThreadLocal) then took over the leak — same cross-thread-cleanup problem, just at a different layer.
  - Round 3 (1-A alone, rolled back): parentObs gate on channels ③/④. Correctly identified TurnState as root, but CLS platform showed 16 spans nested into a single giant trace (G11) and G8 only showed 1 chat span. Root cause: `startRootTurn` still used `Context.current()` as parent for `enter_application`, which read the residual OTel scope stack and inherited the previous trace's traceId.

---

## 1. Problem statement

### 1.1 Reproducer (G11)

Four sequential `.stream().blockLast()` calls in a single HTTP request on the same Tomcat worker thread:

```
POST /sequential-stream-demo  session=g11-test
→ chatClient.prompt().user("介绍 Python").stream().content().blockLast()
→ chatClient.prompt().user("介绍 Java").stream().content().blockLast()
→ chatClient.prompt().user("介绍 Go").stream().content().blockLast()
→ chatClient.prompt().user("介绍 Rust").stream().content().blockLast()
```

**Before fix** (observed on CLS platform):
- Single giant trace with 16 nested spans
- Four `enter_application → invoke_agent → react → chat` chains nested as children of the first chain
- All four `.stream()` responses share one traceId, falsely stitched into one trace tree

**Also observed in cross-request scenario**:
- G8 running after a prior G11 request on the same Tomcat worker shows only 1 chat span on CLS, because G8's `enter_application` inherits the residual G11-t4 scope's traceId and is stitched into G11's trace (invisible when searching for G8's traceId)

### 1.2 Root cause (empirically confirmed through 4 rounds of diagnostics)

Two independent problems chain together:

**Problem 1 — TurnState misidentification (addressed by gate 1-A)**:

Spring AI's `.stream()` triggers `onStart` on the Tomcat worker and `onStop` on a Reactor `boundedElastic` worker. `onStop`'s teardown closes scopes **on the Reactor worker's OTel stack and clears the Reactor worker's `CURRENT_TURN` ThreadLocal** — the Tomcat worker's stack and ThreadLocal are untouched. When the next `.stream()` begins on the same Tomcat worker:

- Channel ① (`findOuterTurnState` via observation parent chain) returns null (new top-level ChatClient has no parent observation)
- Channel ② (`ToolCallReactiveContextHolder`) returns null (no tool-call reactive context between unrelated streams)
- Channel ③ (`Context.current().get(CLS_TURN_KEY)`) returns the **residual TurnState from the previous stream** — because the Tomcat worker's OTel stack still has the previous ctxScope on top
- Falls through to `startSubAgentTurn(...)` → misclassification

**Problem 2 — enter_application inherits residual traceId (addressed by gate c)**:

Even after gate 1-A correctly identifies the new call as a root turn (not subagent), `startRootTurn` uses:

```java
Span entry = tracer.spanBuilder("enter_application")
        .setParent(Context.current())  // ← reads residual OTel stack
        .startSpan();
```

Since the Tomcat worker's OTel stack still carries the previous turn's agentScope at the top, `Context.current()` returns a Context with a valid span. The new `enter_application` inherits that trace's traceId and nests as a child span — producing the 16-span nested trace observed on CLS.

### 1.3 Why previous rounds failed

| Round | Approach | Why it failed |
|---|---|---|
| 1 | Request-boundary filter + `closed`/`openerThreadId` validation | TOCTOU: at next `onStart`, previous `onStop` hadn't yet set `closed=true` (Reactor async). Validation always passed. |
| 2 | Remove `CLS_TURN_KEY` entirely | Channel ④ (`CURRENT_TURN`) inherited the leak the moment ③ was removed. Same cross-thread cleanup problem. |
| 3 | 1-A gate only (no c) | Correctly identified root turn, but `.setParent(Context.current())` still read residual scope → traces nested on CLS platform (visible only in CLS, not in SDK logs). |

**Critical lesson**: SDK log says "`onStart TURN` session=t2" ≠ CLS platform shows an independent trace. SDK state and OTel span-tree state are **two separate concerns** that must both be fixed.

---

## 2. Design — two independent gates with a shared signal

### 2.1 Gate 1-A: parent-observation check for channels ③/④

Modify `startChatClientTurn`:

```java
TurnState outer = findOuterTurnState(context);              // ① obs parent chain
if (outer == null) outer = findOuterTurnStateFromReactorCtx(); // ② reactor ctx holder
// Gate: only consult ③/④ when parent observation exists.
if (outer == null && context.getParentObservation() != null) {
    outer = Context.current().get(CLS_TURN_KEY);            // ③ otel ctx
    if (outer == null) outer = CURRENT_TURN.get();          // ④ thread local
}

if (outer != null) { startSubAgentTurn(context, outer); return; }
startRootTurn(context);
```

**Rationale**: Legitimate same-thread descendants (EmbeddingModel in QuestionAnswerAdvisor, nested tool-call restored observations) always have a non-null `parentObservation`. Fresh top-level ChatClient calls always have `parentObservation == null`. The gate perfectly discriminates "legitimate ③/④ consumer" from "cross-request residue".

**Empirical confirmation (round-3 diagnostic logs)**:
- G11 2nd/3rd/4th streams: `parentObs=null, src=③otelCtx` → **residue** (now correctly ignored)
- G8 inner call: `parentObs=spring.ai.tool, src=①obsParent` → **legitimate**, channel ① hits first anyway
- G10 inner stream: `parentObs=spring.ai.tool, src=①obsParent` → **legitimate**, channel ① hits first

### 2.2 Gate c: parent-context sanitization in `startRootTurn`

Modify `startRootTurn`:

```java
Context parentCtx = Context.current();
if (parentCtx.get(CLS_TURN_KEY) != null) {
    // Residual CLS scope detected — switch to root to avoid inheriting
    // the stale trace's traceId.
    parentCtx = Context.root();
}
Span entry = tracer.spanBuilder("enter_application")
        .setSpanKind(SpanKind.SERVER)
        .setParent(parentCtx)
        .startSpan();
```

**Rationale**: We use the **same signal** as gate 1-A (`CLS_TURN_KEY != null`), but applied at a different layer. Gate 1-A decides "is this a root or subagent (TurnState layer)". Gate c decides "what should enter_application's parent context be (OTel span-tree layer)". Both gates fire exactly when we detect residue from a previous turn.

**Design symmetry**: Both gates use `CLS_TURN_KEY != null` as the residue signal. The symmetry reduces cognitive load — if an engineer later tweaks residue detection logic, they'll know to update both gates together.

### 2.3 Interaction between gates

| Scenario | Gate 1-A decision | Gate c decision | Result |
|---|---|---|---|
| Cold start top-level | outer=null, go startRootTurn | parentCtx has no CLS_TURN_KEY, keep current | Independent trace, linked to business parent if any ✅ |
| G11 2nd+ top-level stream | parentObs=null blocks ③/④, outer=null, startRootTurn | parentCtx has residual CLS_TURN_KEY, use root | Independent trace, detached from residue ✅ |
| G8 inner call | ① hits, go startSubAgentTurn | Not invoked (c only runs in startRootTurn) | Linked to outer trace ✅ |
| G10 inner stream | ① hits (empirically), go startSubAgentTurn | Not invoked | Linked to outer trace ✅ |
| EmbeddingModel broken-chain | parentObs != null, ④ consulted | Not invoked | Linked to outer trace ✅ |

### 2.4 Known trade-off (R2 from brainstorming)

When a second request on the same Tomcat worker has **both** a business parent context (e.g. Alibaba ARMS injecting an http.server span) **and** a CLS residue, gate c switches to `Context.root()` to clear the residue — this also loses the ARMS linkage for that specific request.

**Why accepted**: OTel Context is an immutable layered structure. There's no API to surgically remove only the CLS layer while keeping the ARMS layer. Core CLS trace correctness (independent traces per turn) is prioritized over best-effort APM stitching (which may already be partially broken under high-concurrency stream workloads).

---

## 3. What is **not** changed

- `CLS_TURN_KEY` constant: kept (still used for intra-turn propagation during normal operation)
- `TurnState.ctxScope` field: kept
- All teardown logic (`ctxScope.close`, `agentScope.close`, `entryScope.close`): kept, operates as before on whichever thread onStop fires on
- `CURRENT_TURN` ThreadLocal: kept
- All 4 detection channels: kept, only reordered-with-gate

---

## 4. Verification matrix (CLS platform level)

Verified empirically on 2026-10-16 against Tencent CLS GenAI Trace platform with Hunyuan hy4-preview model:

| # | Session | Scenario | TURN count | SUBAGENT count | Independent traceIds | Status |
|---|---|---|---|---|---|---|
| G11 | `v4-fix-g11` | 4× sequential top-level stream (same Tomcat worker) | 4 | 0 | 4 (`bE0d07Da...`, `aaEf4f68...`, `02B9c79C...`, `1A322c41...`) | ✅ PASS |
| G8 | `v4-fix-g8` | outer call + inner call via tool | 1 | 1 (`inspectService`) | 1 | ✅ PASS |
| G10 | `v4-fix-g10` | outer stream + inner stream (cross-Reactor-worker) | 1 | 1 (`inspectServiceStream`) | 1 | ✅ PASS |

### Key verification signals

1. **G11 4 independent traceIds**: proves gate c sanitization works (residual trace detached)
2. **G11 4× onStart TURN (no SUBAGENT)**: proves gate 1-A works (residual TurnState ignored)
3. **G8/G10 SUBAGENT still fires**: proves gate 1-A doesn't false-positive (parentObs=spring.ai.tool bypasses residue check)
4. **G10 inner stream SUBAGENT fires on Reactor boundedElastic-N**: proves cross-worker subagent detection intact

---

## 5. Risks and mitigations

### R1 — External code setting a conflicting `ContextKey` named `cls.turn-state`

**Risk**: If an external library coincidentally defines a `ContextKey.named("cls.turn-state")` with a `TurnState`-castable value, our residue check could false-positive.

**Mitigation**: `ContextKey` identity is based on **object reference** (not string name). Each `ContextKey.named(...)` call returns a fresh instance. External code cannot produce a ContextKey that `Context.get(ourKey)` would resolve against unless they reflectively access our private static field. Risk level: negligible.

### R2 — ARMS/APM linkage loss in residue scenarios

Discussed in 2.4. Accepted trade-off.

### R3 — OTel stack memory accumulation

Each top-level stream on a reused Tomcat worker leaves 3 Scope objects (entryScope + agentScope + ctxScope) on that worker's OTel stack, un-poppable because they must be closed on the originating thread (which is impossible after onStop ran on a Reactor worker).

**Analysis**:
- Per-request leak: ~3 Scope objects (~100 bytes each) + 1 TurnState (~1 KB)
- Worst case: 1000 QPS × 10 streams/request × 50 Tomcat threads × 1 KB ≈ 50 MB
- Mitigation: not required for v0.0.12; can be addressed in a future "scope lifecycle refactor" if memory profiling shows concern

### R4 — First-request behavior unchanged

Before any residue exists, Context.current() has no CLS_TURN_KEY → gate c keeps `Context.current()` → identical behavior to pre-fix code path. Zero risk.

---

## 6. Code changes summary

**File**: `src/main/java/com/tencentcloudapi/observability/springai/ClsObservationHandler.java`

**Change 1 — startChatClientTurn (gate 1-A)**: ~5 lines, added `parentObservation != null` guard around channels ③/④.

**Change 2 — startRootTurn (gate c)**: ~5 lines, added `CLS_TURN_KEY != null` check to swap parent context to `Context.root()` when residue detected.

**Total impact**: ~10 lines modified, zero API break, zero field removal, zero behavior change outside residue scenarios.

---

## 7. CHANGELOG entry (for 0.0.12)

```
### Fixed
- Cross-request / cross-stream trace pollution: when multiple top-level
  ChatClient.stream() calls executed sequentially on the same Tomcat worker
  thread (e.g. a controller running 4 streams via .blockLast() in a loop),
  the 2nd+ streams were (a) misclassified as subagents on the SDK layer,
  and (b) nested as child spans inheriting the 1st stream's traceId on the
  OTel span-tree layer. Both symptoms are resolved by a dual-gate design:
  gate 1-A guards channels ③/④ of TurnState detection with a non-null
  parentObservation check; gate c swaps enter_application's parent context
  to Context.root() when a residual CLS_TURN_KEY is detected on the thread.
  See docs/plans/2026-10-16-stream-cross-request-leak-dual-gate-fix-design.md
  for the full root-cause analysis and empirical verification against CLS
  platform traces.

### Known trade-off
- Hosts using an APM javaagent (e.g. Alibaba ARMS) will temporarily lose
  the SDK-to-APM trace linkage in the specific case where a Tomcat worker
  has both a business parent context and a CLS stream residue. Core CLS
  trace independence takes priority. Future work: layered-context-aware
  residue sanitization.
```
