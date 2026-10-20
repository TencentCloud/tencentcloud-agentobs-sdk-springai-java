# Fix: G6 scope-stack LIFO violation in `ClsObservationHandler` TURN teardown

- **Date**: 2026-10-16
- **Status**: Approved (brainstorming done 2026-10-16; Q1=①, Q2=c, Q3=b, Q4=c, Q5=①, Q6=b)
- **Scope**: `ClsObservationHandler.onStop(...)` — generic span-close block + root-turn teardown branch + subagent-turn teardown branch
- **Risk**: Low (3 localised edits in one method, no public API change)

---

## 1. Problem

**G6** reproducer: inside a single HTTP request, call `ChatClient.prompt(...).call()` **twice sequentially**
on the same thread. Expected: two independent top-level traces (one per `ChatClient` turn).
Observed: the second turn's `enter_application` span is attached as a child of the **first turn's
`agent` span**, producing a single nested trace instead of two sibling traces.

### Root cause (established by SCOPE-DIAG instrumentation on `0.0.10`)

`startRootTurn()` opens three OTel scopes on the thread stack in order **A → B → C**:

```
A  entryScope = entrySpan.makeCurrent()                         // enter_application
B  agentScope = agentSpan.makeCurrent()                         // invoke_agent
C  ctxScope   = Context.current().with(CLS_TURN_KEY, ts)        // TurnState attachment
                        .makeCurrent()
```

The root teardown branch of `onStop()` closes them in order **B → A → C** (via two code paths —
the generic span-close block closes `s.scope` = `agentScope` first, then the root branch closes
`entryScope` then `ctxScope`). That order violates LIFO.

OTel's `ArrayBasedContextStorage.Scope.close()` **silently no-ops** if the scope being closed is
not the current top of the stack. SCOPE-DIAG confirmed:

```
root-stop TURN#1  AFTER entryScope.close:  spanId=a842455766abfd1c  ← unchanged (not top)
root-stop TURN#1  AFTER ctxScope.close:    spanId=a842455766abfd1c  ← still agentSpan
root-start TURN#2 BEFORE any scope open:   spanId=a842455766abfd1c  ← leaked
TURN#2 enter_application setParent(current)  → parented to TURN#1 agentSpan
```

So after TURN#1 "ends" the stack still contains `[entryScope, agentScope]`; TURN#2's
`enter_application` picks `agentScope`'s span as its parent via Spring AI's
`ParentSpanObservationFilter`.

### Why G1–G5 / G7 did not catch this

The LIFO violation only manifests when **the same thread opens a new root TURN after a previous
one closed on the same thread**. G1–G5 either run one TURN per request (G1, G2, G3) or use a fresh
thread between turns (G5-replicas). G4's subagent nests inside, not after, the outer TURN. The
subagent teardown path has an **analogous** LIFO violation (see §3.2) but is similarly masked
in current regressions.

---

## 2. Solution — restore strict LIFO in TURN teardown

### 2.1 Design principles

1. **LIFO is a precondition, not a nice-to-have.** OTel's scope storage is a thread-local
   `ArrayDeque`. Closing out of order fails silently and corrupts the Context for subsequent
   observations. We fix the ordering at the source.
2. **Single ownership of TURN-related scopes.** The generic span-close block currently closes
   `s.scope` for every observation including turn observations. For turn observations
   `s.scope == agentScope` is one of three interrelated scopes that must close together in LIFO
   order. We move its close out of the generic block so **the per-turn teardown branches
   exclusively own the full teardown**.
3. **Fail loud, not silent.** The current `catch (Throwable ignored) { /* noop */ }` on each
   `close()` is what hid this bug for weeks. We keep the try/catch (don't want a stray close
   failure to crash the request) but downgrade "ignored" to `LOG.warn(...)` on the scope-close
   calls that matter. Normal operation after the fix should produce zero such warnings.

### 2.2 Fix at a glance

| Location | Change |
|---|---|
| Generic span-close block | Wrap `s.scope.close()` + `s.span.end()` with `if (!s.isTurn)` so turn observations skip it and are handled by their respective turn branches. Convert `ignored` on scope close to `LOG.warn`. |
| Root turn teardown branch | Close scopes in strict LIFO: **ctxScope (C) → agentScope (B) → entryScope (A)**; end each span right after its scope. Convert each scope close `catch` to `LOG.warn`. |
| Subagent turn teardown branch | Close scopes in strict LIFO: **ctxScope (C) → agentScope (B)**; subagent has no entryScope. Move the `agentScope.close` + `agentSpan.end` from the generic block into this branch (same reason as root). |

### 2.3 Code sketch

Generic block (around current line 959):

```java
// For isTurn observations the scope/span is owned by the per-turn teardown
// branches below and must close in strict LIFO with the other turn scopes.
try { if (s.toolInfoScope != null) s.toolInfoScope.close(); }
catch (Throwable t) { LOG.warn("[cls-springai] toolInfoScope.close failed: {}", t.toString()); }
if (!s.isTurn) {
    try { s.scope.close(); }
    catch (Throwable t) { LOG.warn("[cls-springai] obs scope.close failed: {}", t.toString()); }
    try { s.span.end(); } catch (Throwable ignored) {}
}
```

Root turn branch (replacing current close sequence; SCOPE-DIAG blocks retained
during verification per §6):

```java
if (ts2 != null) {
    // (C) ctxScope — top of stack, last opened in startRootTurn
    try { if (ts2.ctxScope != null) ts2.ctxScope.close(); }
    catch (Throwable t) { LOG.warn("[cls-springai] root ctxScope.close failed: {}", t.toString()); }
    // (B) agentScope — moved out of generic block
    try { s.scope.close(); }
    catch (Throwable t) { LOG.warn("[cls-springai] root agentScope.close failed: {}", t.toString()); }
    try { s.span.end(); } catch (Throwable ignored) {}
    // (A) entryScope — first opened, last closed
    try { ts2.entryScope.close(); }
    catch (Throwable t) { LOG.warn("[cls-springai] root entryScope.close failed: {}", t.toString()); }
    try { ts2.entrySpan.end(); } catch (Throwable ignored) {}
}
CURRENT_TURN.remove();
```

Subagent branch:

```java
if (ts2 != null) {
    // (C) ctxScope first
    try { if (ts2.ctxScope != null) ts2.ctxScope.close(); }
    catch (Throwable t) { LOG.warn("[cls-springai] subagent ctxScope.close failed: {}", t.toString()); }
}
// (B) agentScope — moved out of generic block
try { s.scope.close(); }
catch (Throwable t) { LOG.warn("[cls-springai] subagent agentScope.close failed: {}", t.toString()); }
try { s.span.end(); } catch (Throwable ignored) {}
// CURRENT_TURN restoration unchanged ...
```

### 2.4 Why span.end() stays glued to its scope.close

`Span.end()` is a span-lifecycle API, independent of the scope stack. We keep each span's `end()`
immediately after its scope's `close()` to preserve the current semantics ("span ends when its
scope is torn down"), with only the ordering between the three (ctx/agent/entry) corrected.

---

## 3. Scope of change

### 3.1 In scope

- `ClsObservationHandler.onStop(...)` — the single `else` branch after the early-return guards
  (contains both the generic span-close block and the root/subagent split).
- Both root and subagent teardown paths, per the brainstorm Q2=c decision.

### 3.2 Not touched (justified)

| Location | Reason |
|---|---|
| `switchToNextRoundReact` (react round-switch scope) | Single scope close, no nesting; LIFO trivially holds. |
| `startRootTurn` / `startSubAgentTurn` open order | Keeping open order unchanged (Q1=①). We only fix close order. |
| `toolInfoScope` | Opened and closed inside the same observation lifecycle, not a turn-level scope. |
| `activeReactScope` | Closed in the Z-2 synthetic-react cleanup block directly before the `isTurn` branch; order relative to turn scopes was already correct and remains so. |

---

## 4. Interaction with existing SDK behavior

| Area | Impact |
|---|---|
| G1 (single root turn, `.call()`) | No visible change; close order now LIFO but outcome was already correct. |
| G2 (streaming / Reactor-ctx propagation) | Unaffected — fix is on sync teardown path, which `.stream()` uses only after Reactor hands observations back to the originating thread; same thread, LIFO still correct. |
| G3 (tool-call nested spans) | Unaffected — tool observations are non-turn, so new `if (!s.isTurn)` guard still runs their close. |
| G4 (subagent) | Latent LIFO violation on subagent teardown is now also fixed. Expected: no regression; small chance of improved trace shape in edge cases where outer-TURN continuation was previously picking the subagent span as parent. |
| G5 (concurrent subagents) | Each worker thread has its own scope stack; fix applies per-thread uniformly. |
| G6 (sequential root turns same thread) | **Primary target** — TURN#2 now observes a clean scope stack, becomes its own root trace. |
| G7 (long-running / round-switch) | Round-switch logic is untouched (§3.2). |
| User code | Zero API change. Opt-out not needed (fix is strictly a correction of broken ordering). |

---

## 5. Explicit non-goals

- Not changing scope **open** order (Q1=①). Open order has implicit downstream consumers
  (`findOuterTurnState` walks observations, `CLS_TURN_KEY` lookup assumes ctx is the inner-most
  attachment); shuffling it risks hidden breakage.
- Not replacing the scope-stack mechanism with `Context.makeCurrent()` wrappers or an alternative
  attachment scheme (brainstorm Q1 options ③/④, both ruled out).
- Not adding a config knob. The fix corrects a defect; there's no legitimate use case for opting
  into the broken behaviour.
- Not touching `switchToNextRoundReact` or `toolInfoScope` paths (§3.2).

---

## 6. Verification plan

Per brainstorm Q4=c and Q6=b, verification runs on **true `0.0.10` jar** with SCOPE-DIAG probes
**still in place**, then probes removed in a follow-up commit.

### 6.1 G6 primary (expected pass post-fix)

Reproducer: single HTTP request issues two sequential `ChatClient.prompt(...).call()`.

Expected SCOPE-DIAG output (one TURN#1 teardown):

```
root-stop TURN#1 BEFORE any close:            spanId=<agent1>
root-stop TURN#1 AFTER ctxScope.close:        spanId=<agent1>      ← still agent, ctx popped
root-stop TURN#1 AFTER agentScope.close:      spanId=<entry1>      ← agent popped, entry on top
root-stop TURN#1 AFTER entryScope.close:      spanId=0000..0000    ← fully unwound
root-start TURN#2 BEFORE any scope open:      spanId=0000..0000    ← clean
```

Expected CLS backend: two independent trace IDs, each with `enter_application` as root.

### 6.2 Regression (expected pass unchanged)

Full G1–G5 + G7 suite executed against the rebuilt `0.0.10` jar (the suite we previously
mistakenly ran against stale `0.0.9` artifacts). Each case's acceptance criteria from its own
design doc.

### 6.3 Cleanup commit

After both pass, remove the four `SCOPE-DIAG` LOG.info blocks from the root-turn teardown branch
(brainstorm Q6=b). Commit separately with message `chore: remove G6 scope-stack diagnostic logs`.

---

## 7. Rollout

Patch bump `0.0.10 → 0.0.11`.

CHANGELOG entry sketch:

> ### Fixed
> - **Sequential top-level `ChatClient` turns on the same thread now produce independent traces.**
>   Previously, when a single HTTP request issued two consecutive `ChatClient.prompt(...).call()`
>   invocations on the same thread, the second turn's `enter_application` span was attached as a
>   child of the first turn's `invoke_agent` span, producing a single nested trace instead of two
>   siblings. Root cause: `ClsObservationHandler` closed the turn's OTel scopes in a non-LIFO
>   order (`agentScope → entryScope → ctxScope` vs open order
>   `entryScope → agentScope → ctxScope`), causing OTel's `ArrayBasedContextStorage` to silently
>   skip the mismatched closes and leak the agent scope to the next turn. The teardown now closes
>   in strict LIFO (`ctxScope → agentScope → entryScope` for root turns, `ctxScope → agentScope`
>   for subagent turns), and failing scope closes now log at WARN instead of being silently
>   swallowed so similar regressions surface immediately.
