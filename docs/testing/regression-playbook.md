# Regression Playbook — `tencentcloud-agentobs-sdk-springai-java`

- **Audience**: SDK maintainers, anyone pushing a non-trivial change to
  `ClsObservationHandler` / `ClsObservability` / baggage / scope lifecycle.
- **Purpose**: single-source definition of the 12 verification cases
  (G1 – G10) that collectively confirm the SDK does not regress on any
  of the historically fragile code paths (ReAct round-switch, subagent
  nesting, Reactor cross-thread observation propagation, OTel scope
  stack LIFO, concurrent streaming, ChatMemory recall, tool error
  swallow/rethrow, sequential sibling ChatClients on the same thread).
- **How to run everything**: `./scripts/run-regression.sh` — one command,
  self-contained, 10–15 minutes end-to-end (dominated by G4's 3-layer
  LLM chain and the G8/G9/G10 subagent runs).

---

## 1. Prerequisites

| Requirement | Why | How to check |
|---|---|---|
| `~/.agentobs-springai/config.env` with `HUNYUAN_API_KEY`, `CLS_ENDPOINT`, `CLS_TOPIC_ID`, `CLS_SECRET_ID`, `CLS_SECRET_KEY` | demo-springboot needs real LLM + CLS creds | `test -f ~/.agentobs-springai/config.env && grep -c HUNYUAN_API_KEY ~/.agentobs-springai/config.env` |
| JDK 17+ | Spring Boot 3.3 / Spring AI 1.x require it | `java -version` |
| `mvn` | build & launch | `mvn -v` |
| `curl`, `lsof`, `grep`, `awk` | script internals | — |
| TCP port 8080 free | demo listens there (override with `PORT=... ./scripts/run-regression.sh`) | `lsof -i:8080` |

Override points (env vars read by the script):

- `BASE_URL`   — default `http://localhost:8080`
- `PORT`       — default `8080`
- `CURL_TIMEOUT` — default `180` seconds per request
- `CLS_CONFIG_ENV` — default `~/.agentobs-springai/config.env`

---

## 2. One-command run

```bash
./scripts/run-regression.sh
```

### Expected output shape

```
=== Regression suite 20261016-113200 ===
    log file   : /tmp/regression-20261016-113200.log
    outputs dir: /tmp/regression-20261016-113200

>> Installing SDK to ~/.m2 ...
>> Starting demo-springboot (log=/tmp/regression-20261016-113200.log) ...
   ... started after 2s

>> Running 13 regression sub-cases (G1 .. G10) ...


=== Verdict table ===
[ 1/13] G1-plain       PASS  HTTP 200  t=  7.2s  TURN=1 spans=16 WARN=0
[ 2/13] G1-rich        PASS  HTTP 200  t=  5.4s  TURN=1 spans=23 WARN=0
[ 3/13] G2 turn1       PASS  HTTP 200  t= 11.7s  session TURNs so far=1
[ 4/13] G2 turn2       PASS  HTTP 200  t= 15.5s  TURNs=2  recall=yes WARN=0
[ 5/13] G3-soft        PASS  HTTP 200  t=  5.2s  TURN=1 WARN=0
[ 6/13] G3-hard        PASS  HTTP 200  t=  2.1s  TURN=1 outcome=exception WARN=0
[ 7/13] G7             PASS  HTTP 200  t=  6.0s  TURN=1 spans=26 WARN=0
[ 8/13] G8 sub-cc      PASS  HTTP 200  t= 43.5s  TURN=1 WARN=0
[ 9/13] G9 sub-sc      PASS  HTTP 200  t= 58.5s  TURN=1 WARN=0
[10/13] G10 sub-ss     PASS  HTTP 200  t= 62.9s  TURN=1 WARN=0
[11/13] G4 deep-sub    PASS  HTTP 200  t= 78.5s  TURN=1 SUBs(cum)=3 WARN=0
[12/13] G5 10-concur   PASS  HTTP 10/10  sess-A TURNs=5 sess-B TURNs=5 WARN=0
[13/13] G6 primary*    PASS  HTTP 200  t= 16.0s  TURN=2 traces=2 WARN=0

Summary: 13/13 PASS   scope-close WARN total: 0   log: /tmp/regression-20261016-113200.log
```

Any non-zero exit code or any FAIL verdict is a regression. Open the
server log at the printed path and search for the failing session id
(e.g. `g6-primary-20261016-113200`) to inspect the full trace.

---

## 3. Case catalogue

Each case below is driven by one or two `curl` invocations against the
running demo-springboot. The playbook lists what the case proves, how
to run it standalone, and how the regression script asserts success.

> Why repeat these manually when the script does it all? Because when a
> single case fails under CI you'll want to re-run only that one from
> your laptop with full control over timing, logging level, and the
> ability to attach a debugger.

### 3.1 G1-plain — top-level `.stream()`, plain LLM

- **Endpoint**: `POST /chat/stream-plain`
- **Proves**: `ChatModel.stream()` observations get parented to the
  caller's `invoke_agent`/`enter_application` even when Reactor hops
  the Flux onto a worker thread. Depends on
  `Hooks.enableAutomaticContextPropagation()` installed by the SDK.
- **Expected trace shape**: 1 TURN + 1 `gen_ai.client.operation` +
  advisor spans sharing a single trace id.
- **Standalone invocation**:
  ```bash
  curl -X POST http://localhost:8080/chat/stream-plain \
       -H 'Content-Type: application/json' \
       -H "X-Session-Id: g1-plain-manual" \
       -d '{"q":"用一句话介绍你自己"}'
  ```
- **Assertion**: HTTP 200 ∧ exactly 1 TURN start for the session ∧
  spans in that trace ≥ 2 ∧ no `cls-springai .* close failed` WARN.

### 3.2 G1-rich — top-level `.stream()` + RAG + tool

- **Endpoint**: `POST /chat/stream-rich`
- **Proves**: ReAct round-switch + QuestionAnswerAdvisor retrieval span
  + tool execute span all parent correctly under the same trace, even
  across Reactor thread hops.
- **Standalone invocation**:
  ```bash
  curl -X POST http://localhost:8080/chat/stream-rich \
       -H 'Content-Type: application/json' \
       -H "X-Session-Id: g1-rich-manual" \
       -d '{"q":"查一下北京的天气"}'
  ```
- **Assertion**: same shape as G1-plain, more spans (~17–23).

### 3.3 G2 — ChatMemory multi-turn

- **Endpoint**: `POST /chat/memory` (invoked twice in sequence with the
  **same** `X-Session-Id`)
- **Proves**: `MessageChatMemoryAdvisor` is correctly layered into the
  Observation chain and the second turn semantically recalls the first.
- **Standalone invocation**:
  ```bash
  SID="g2-manual-$(date +%s)"
  curl -X POST http://localhost:8080/chat/memory -H 'Content-Type: application/json' \
       -H "X-Session-Id: $SID" -d '{"q":"我是小明"}'
  curl -X POST http://localhost:8080/chat/memory -H 'Content-Type: application/json' \
       -H "X-Session-Id: $SID" -d '{"q":"我是谁？"}'
  ```
- **Assertion**: both HTTP 200 ∧ exactly 2 TURN starts for the session
  ∧ second response contains the string "小明".

### 3.4 G3-soft — tool throws, default processor swallows

- **Endpoint**: `POST /error-demo/soft`
- **Proves**: Spring AI's default
  `ToolExecutionExceptionProcessor` swallows the exception into a
  synthesized tool-result message; from Micrometer's point of view the
  observation never saw an error. The SDK's `onStop` must still close
  the span cleanly. Response is HTTP 200 with normal text.
- **Assertion**: HTTP 200 ∧ 1 TURN ∧ no scope WARN.

### 3.5 G3-hard — tool throws, custom processor rethrows

- **Endpoint**: `POST /error-demo/hard`
- **Proves**: Custom processor rethrows → Micrometer sees `onError` on
  the tool span, step span, and ChatClient span. The SDK's `onError`
  path marks the span `StatusCode.ERROR`, stamps the exception, and
  still closes scopes. The HTTP layer wraps the raised
  `ToolExecutionException` into a JSON `outcome: "exception"`.
- **Assertion**: HTTP 200 ∧ response JSON has `"outcome":"exception"` ∧
  1 TURN ∧ no scope WARN.

### 3.6 G4 — deep-chain subagents (3 layers)

- **Endpoint**: `POST /subagent-deep-demo`
- **Proves**: A tool handler can open a nested `ChatClient`, which in
  turn calls another tool handler that opens a further nested
  `ChatClient`, three deep. The SDK's `TurnState.outer` stack must
  correctly nest/unnest, and all generated spans must share one trace.
- **Assertion**: HTTP 200 ∧ 1 TURN for the session (subagents reuse
  the outer turn by design — "1 trace = 1 entry = 1 turn = N
  subagents" per CLS v5) ∧ no scope WARN.

### 3.7 G5 — 10 concurrent streaming requests

- **Endpoint**: `POST /chat/stream-plain` × 10 fired in parallel across
  2 sessions × 5 requests each.
- **Proves**: Concurrent `.stream()` turns on different threads don't
  cross-contaminate TurnState. Per-session TURN counters must each
  reach exactly 5 (not 10, not 7).
- **Assertion**: all 10 HTTP 200 ∧ session A has exactly 5 TURN starts
  ∧ session B has exactly 5 TURN starts ∧ no scope WARN.

### 3.8 G6 ⭐ — sequential sibling ChatClients on the same thread

- **Endpoint**: `POST /multi-agent-demo` (one request → two
  `ChatClient.call()` invocations: translator then summarizer)
- **This is the LIFO primary regression**. Before the 0.0.11 fix the
  second `.call()` opened its `enter_application` span inheriting the
  first `.call()`'s leaked `agentScope`, producing a nested trace
  instead of two sibling top-level traces.
- **Proves (post-fix)**: the OTel scope stack is strictly LIFO
  (`ctxScope → agentScope → entryScope`) in `onStop`, so after TURN#1
  teardown the thread's current span becomes invalid and TURN#2 opens
  as its own root.
- **Assertion** (strictest in the suite):
  - HTTP 200
  - exactly **2** TURN starts for the session
  - the two TURN starts carry **two distinct trace ids**
  - no scope-close WARN

Manual verification is useful when debugging:
```bash
grep 'onStart  TURN  session=g6-' /tmp/regression-*.log | awk '{print $NF}' | sort -u
# should print 2 distinct traceId=... lines
```

### 3.9 G7 — sync `.call()` + tool (non-nested baseline)

- **Endpoint**: `POST /chat`
- **Proves**: the "boring" happy path still works end-to-end. Guards
  against regressions where fixes for the streaming / nested-subagent
  paths accidentally break synchronous single-level tool execution.

### 3.10 G8 — single-layer subagent, sync + sync

- **Endpoint**: `POST /subagent-demo`
- **Proves**: outer `.call()` + inner `.call()` triggers the subagent
  branch of `onStop` (the one modified by the LIFO fix: it now closes
  `ctxScope` then `agentScope` + ends `agentSpan`). Pure
  single-threaded execution — no Reactor involvement.

### 3.11 G9 — single-layer subagent, outer stream + inner sync

- **Endpoint**: `POST /subagent-demo/stream`
- **Proves**: outer `.stream()` hops onto a Reactor worker thread
  before the subagent observation opens, yet the subagent's
  `TurnState` is still located correctly (either via Reactor
  Context propagation, Micrometer parent-observation chain, or
  the thread-local fallback). The subagent `onStop` LIFO close
  still works when the open+close happen on different threads.

### 3.12 G10 — single-layer subagent, outer stream + inner stream

- **Endpoint**: `POST /subagent-demo/stream-deep`
- **Proves**: the strictest Reactor case — both the outer and the
  inner tool-triggered ChatClient run on Reactor-scheduled threads.
  Still 1 trace, 1 TURN, 1 subagent, no scope WARN.

---

## 4. Looking at results in the CLS console

After a regression run, each case stamps `gen_ai.session.id` as
`g{N}-{suffix}-{STAMP}`. In the CLS GenAI Trace tab, filter by:

```
gen_ai.session.id = g6-primary-20261016-113200
```

(replace `20261016-113200` with the stamp printed at the top of your
own run). Each session's trace tree should match the "Expected trace
shape" bullets in §3.

### G6 specifically

Set the filter to `gen_ai.session.id = g6-primary-{STAMP}` and expect
**two separate trace rows** in the trace list — not one trace with
nested TURNs. If you see a single trace with two nested
`enter_application` spans the LIFO fix regressed.

---

## 5. Failure triage cheat sheet

| Symptom | Likely cause |
|---|---|
| `>> Starting demo-springboot ...` hangs > 2 min | Port 8080 busy; `.m2` jar stale; missing config.env |
| G1-plain / G1-rich fail with `TURN=0` | `enableAutomaticContextPropagation()` hook not installed; check `ClsObservability.install()` logs. |
| G2 turn2 shows `recall=0` | ChatMemoryAdvisor not wired, or the model picked a non-recalling path; look at `[cls-springai] onStart ADVISOR` lines for the two turns. |
| G3-hard shows `outcome=missing` | Custom `ToolExecutionExceptionProcessor` not resolving the error path; check `ErrorToolsDemoController` + Spring AI version. |
| G5 shows `TURNs=10` on one session | Thread-local cross-contamination across concurrent streams — check `CURRENT_TURN` handling and the TURN start / stop pairing. |
| G6 shows `traces=1` with `TURN=2` | **LIFO regression** — the scope stack is not being torn down cleanly between the two sibling ChatClients. Compare against the fix in `docs/plans/2026-10-16-g6-scope-lifo-fix-design.md`. |
| G8/G9/G10 show `TURN=0` | subagent not detected (nested ChatClient opened a brand-new root turn); check `findOuterTurnState*()` branches. |
| Any `scope-close WARN > 0` | The teardown branch tried to close a scope that was not current — immediate indicator of a scope-stack-corruption regression. Treat as critical. |

---

## 6. Extending the suite

To add a new case **G11**:

1. If a new endpoint is needed, add a new controller under
   `demo-springboot/.../controller/`.
2. Add a `run_g11()` function in `scripts/run-regression.sh` matching
   the pattern of existing ones (session id = `g11-{short}-${STAMP}`).
3. Append `run_g11 14` to the `main()` call list and bump the total
   count in the "13 regression sub-cases" log line.
4. Document it as §3.13 above; add a row to the triage cheat sheet.
5. Run the full suite once; paste the fresh verdict table into
   `CHANGELOG.md` under the version that introduced the new case.

---

## 7. Related design docs

- [2026-10-14-subagent-support-design.md](../plans/2026-10-14-subagent-support-design.md) — subagent lifecycle (G4, G8, G9, G10)
- [2026-10-15-reactor-context-propagation-design.md](../plans/2026-10-15-reactor-context-propagation-design.md) — Reactor hook (G1-plain, G1-rich, G5, G9, G10)
- [2026-10-16-g6-scope-lifo-fix-design.md](../plans/2026-10-16-g6-scope-lifo-fix-design.md) — scope stack LIFO (G6 primary + G8/G9/G10 subagent branch)
