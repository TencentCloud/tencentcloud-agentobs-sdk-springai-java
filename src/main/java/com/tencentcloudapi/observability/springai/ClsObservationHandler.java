package com.tencentcloudapi.observability.springai;

import io.micrometer.common.KeyValue;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationHandler;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanBuilder;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.ContextKey;
import io.opentelemetry.context.Scope;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Method;
import java.util.ArrayDeque;
import java.util.Collections;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static com.tencentcloudapi.observability.springai.ClsReflect.findTagValue;
import static com.tencentcloudapi.observability.springai.ClsReflect.invokeFirst;
import static com.tencentcloudapi.observability.springai.ClsReflect.invokeRequest;
import static com.tencentcloudapi.observability.springai.ClsReflect.invokeResponse;
import static com.tencentcloudapi.observability.springai.ClsReflect.invokeString;
import static com.tencentcloudapi.observability.springai.ClsReflect.parentContext;
import static com.tencentcloudapi.observability.springai.ClsReflect.truncate;

/**
 * Spring AI → <b>CLS GenAI Trace</b> ObservationHandler.
 *
 * <p>Subscribes to every Spring AI–flavoured Micrometer {@link Observation} and
 * mirrors it into a tree of OpenTelemetry {@link Span}s aligned with the CLS
 * GenAI Trace spec:
 *
 * <pre>
 *   entry (SERVER, gen_ai.span.kind=entry)                     ← one per ChatClient call
 *     └── agent (INTERNAL, gen_ai.span.kind=agent)             ← one per ChatClient call
 *           ├── step  (INTERNAL, gen_ai.span.kind=step)        ← per Advisor
 *           │     ├── chat (CLIENT, gen_ai.span.kind=chat)     ← ChatModel call
 *           │     └── tool (CLIENT, gen_ai.span.kind=tool)     ← tool call
 *           ├── step  (…, operation.name=embedding | retrieval)
 *           ├── chat  (if the model is called directly off ChatClient)
 *           └── tool  (if a tool is called directly off ChatClient)
 * </pre>
 *
 * <p><b>Spec-mandated attributes written on every span</b>:
 * {@code gen_ai.span.kind}, {@code gen_ai.operation.name},
 * {@code gen_ai.session.id}, {@code gen_ai.turn.id}, and — on step/chat/tool —
 * {@code gen_ai.step.id}. The IDs form the chain
 * {@code session → turn ({sessionId}:t{N}) → step ({turnId}:s{N})}, as in the
 * sibling AgentScope SDK, so the two produce isomorphic traces on CLS.
 *
 * <p><b>ID sources</b>:
 * <ul>
 *     <li>{@code session.id} / {@code user.id}: read from {@link ClsBaggage}
 *         (user sets them at the HTTP layer). Falls back to a UUID when unset.</li>
 *     <li>{@code turn.id}: minted on each ChatClient observation using a
 *         process-wide {@link AtomicInteger}.</li>
 *     <li>{@code step.id}: minted on each Advisor / step-kind observation
 *         using a per-turn counter stored on the {@link TurnState}.</li>
 * </ul>
 *
 * <p><b>Prompt / completion / tool-argument content</b> is pulled directly off
 * the {@code *ObservationContext} subclasses via reflection, so users get full
 * content on CLS even when they haven't opted into Spring AI's
 * {@code include-prompt} / {@code include-completion} flags. The keys written
 * follow the CLS spec: {@code gen_ai.input.messages},
 * {@code gen_ai.output.messages}, {@code gen_ai.tool.call.arguments},
 * {@code gen_ai.tool.call.result}.
 *
 * <p><b>Thread safety</b>: a single instance is shared across all threads. All
 * per-trace mutable state lives on the {@link Observation.Context} (which
 * Micrometer guarantees is one-per-observation) via its
 * {@link Observation.Context#put(Object, Object) put}/{@link Observation.Context#get(Object) get}
 * API, so this class holds no shared mutable fields except the bounded
 * {@link #turnCounters} map (per-session monotonic turn counters, access-order
 * LRU, soft cap {@value #MAX_TRACKED_SESSIONS}).
 */
public final class ClsObservationHandler implements ObservationHandler<Observation.Context> {

    private static final Logger LOG = LoggerFactory.getLogger(ClsObservationHandler.class);

    /** Framework identifier reported as {@code gen_ai.agent.type}. */
    private static final String AGENT_TYPE = "spring-ai";

    // Observation name prefixes we care about.
    private static final String SPRING_AI_PREFIX = "spring.ai.";
    private static final String GEN_AI_PREFIX = "gen_ai.";

    private final Tracer tracer;
    private final ClsConfig config;

    /**
     * Soft upper bound on how many distinct sessions we track turn counters for.
     * When exceeded, the least-recently-used session's counter is evicted (its
     * next turn will restart from t1 — acceptable for long-idle sessions).
     * Memory estimate at the cap: ~10000 * (~40 B key + 16 B AtomicInteger +
     * LinkedHashMap overhead) ≈ 2 MB.
     */
    private static final int MAX_TRACKED_SESSIONS = 10_000;

    /**
     * Per-session monotonic turn counter.
     *
     * <p>Each session has its own {@link AtomicInteger}, so {@code turn.id}'s
     * {@code N} is session-local and starts at 1 — matching the spec's
     * {@code gen_ai.turn.id = "{sessionId}:t{N}"} semantics.
     *
     * <p>Backed by an access-order {@link LinkedHashMap} with a bounded size:
     * reads and writes refresh LRU order, and when size exceeds
     * {@link #MAX_TRACKED_SESSIONS} the eldest entry is evicted. Wrapped in
     * {@link Collections#synchronizedMap(Map)} because {@code LinkedHashMap}'s
     * access-order tracking is not thread-safe. QPS impact is negligible —
     * this map is touched exactly once per turn (in {@code startChatClientTurn}),
     * at microsecond granularity.
     */
    private final Map<String, AtomicInteger> turnCounters = Collections.synchronizedMap(
            new LinkedHashMap<String, AtomicInteger>(256, 0.75f, true /* access-order */) {
                private static final long serialVersionUID = 1L;
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, AtomicInteger> eldest) {
                    return size() > MAX_TRACKED_SESSIONS;
                }
            });

    public ClsObservationHandler(Tracer tracer, ClsConfig config) {
        this.tracer = Objects.requireNonNull(tracer, "tracer");
        this.config = Objects.requireNonNull(config, "config");
    }

    /**
     * Log a swallowed telemetry-path exception at DEBUG.
     *
     * <p>Observability must never break or spam the host application, so every
     * internal failure is caught and discarded. Routing them through this single
     * helper makes them observable when the SDK's debug logging is enabled,
     * without adding any noise in production (parameterized, so there is no cost
     * when DEBUG is disabled). Expected control-flow branches — e.g. probing the
     * next candidate reflection getter — intentionally do NOT call this.
     */
    private static void swallow(String site, Throwable t) {
        LOG.debug("[cls-springai] {} failed (swallowed): {}", site, t.toString());
    }

    // ══════════════════════════════════════════════════════════════════════
    //  Which observations do we handle?
    // ══════════════════════════════════════════════════════════════════════

    @Override
    public boolean supportsContext(Observation.Context context) {
        String name = context.getName();
        if (name != null && (name.startsWith(SPRING_AI_PREFIX) || name.startsWith(GEN_AI_PREFIX))) {
            return true;
        }
        // Spring AI 1.0 calls supportsContext *before* setName() when invoked
        // via certain Observation.start()/.observe() paths; name is empty/null
        // but the Context subclass is already the correct one. Fall back to
        // class-name matching so we don't miss any events.
        String ctxClassName = context.getClass().getName();
        return ctxClassName.startsWith("org.springframework.ai.")
                || ctxClassName.contains(".ChatClientObservationContext")
                || ctxClassName.contains(".ChatModelObservationContext")
                || ctxClassName.contains(".AdvisorObservationContext")
                || ctxClassName.contains(".ToolCallingObservationContext")
                || ctxClassName.contains(".EmbeddingModelObservationContext")
                || ctxClassName.contains(".VectorStoreObservationContext");
    }

    // ══════════════════════════════════════════════════════════════════════
    //  Lifecycle
    // ══════════════════════════════════════════════════════════════════════

    @Override
    public void onStart(Observation.Context context) {
        try {
            Classification cls = classify(context);

            // ChatClient observation is the "turn" boundary. We open an EXTRA
            // outer span of kind=entry here, so the tree root matches the CLS
            // spec exactly (entry wraps agent wraps steps/chats/tools).
            if (cls.kind == CLS_ENTRY_WRAPPER) {
                startChatClientTurn(context);
                return;
            }

            // ── Spring AI 2.0 advisor-chain collapsing ───────────────────
            // Spring AI 2.0 emits a `spring.ai.advisor` observation around EACH
            // advisor in the chain (RagAdvisor → ChatMemoryAdvisor → ...), so
            // without collapsing you get `react ▸ react ▸ chat`-style nested
            // trees instead of the flat `react ▸ (chat | execute_tool)` tree
            // the CLS GenAI Trace spec expects. We fold any nested advisor
            // observation into the currently-open react span: no new OTel span
            // is created, no scope is pushed, and the inner chat/tool spans
            // naturally re-parent onto the outermost react. The behaviour is a
            // no-op for Spring AI 1.0 (which only ever emits one advisor obs
            // per turn) because the enclosing-react check will be false.
            TurnState ts = findTurnState(context);
            if (ts != null && "step".equals(cls.kindAttr) && "react".equals(cls.opName)
                    && ts.currentStepId != null) {
                setSpanState(context, SpanState.collapsed(cls));
                if (config.debug()) {
                    LOG.debug("[cls-springai] onStart  obs={}  kind={}  COLLAPSED (nested advisor, stepId={})",
                            context.getName(), cls.kindAttr, ts.currentStepId);
                }
                return;
            }

            // Everything else: a single span mirroring the observation.
            //
            // Parent OTel span: walk the Micrometer parent-observation chain
            // and reuse the first ancestor's SpanState.span as our OTel parent.
            //
            // Rationale: when Spring AI's streaming paths dispatch inner
            // observations onto Reactor BoundedElastic workers, the Reactor
            // automatic context propagation hook carries Micrometer's
            // ObservationThreadLocalAccessor across threads — so parent-
            // observation lookup via Micrometer is correct. But OTel's own
            // Context is NOT auto-propagated by that hook, so Span.current()
            // on a worker thread would return INVALID (or the wrong span),
            // which is exactly what caused each child observation to become
            // a new trace root in 0.0.9. Reading our SpanState through the
            // parent observation's ContextView is thread-safe (Micrometer
            // guarantees parent observation accessibility) and version-agnostic
            // (uses only the public Observation.ContextView API).
            Span parent = resolveParentSpanFromObservation(context);

            // ── Z-2 lazy round switch (chat + embedding) ──────────────────
            // If the previous chat ended with finish_reason==tool_calls, this
            // new chat/embedding is the first child of round N+1. End the
            // previous react (either natively or by force) and open a fresh
            // SDK-synthesised one here, before this span is built — so round
            // N+1's children reparent onto the new react rather than the stale
            // one.
            //
            // Why embedding too (not just chat)? Spring AI's memory/RAG
            // advisors issue an embedding call to re-embed the user turn or
            // store assistant memory before the next chat kicks off. That
            // embedding semantically belongs to the next round (it's the
            // *preparation* for the next LLM call), so switching on it keeps
            // sibling embedding+chat pairs together under their round's react.
            if ((cls.kind == CLS_CHAT || cls.kind == CLS_EMBEDDING) && ts != null
                    && "tool_calls".equalsIgnoreCase(ts.lastFinishReason)) {
                switchToNextRoundReact(ts);
                // After the switch, the OTel Context.current() moved onto the
                // new synthetic react span, so re-read parent for buildSpan.
                // Here Span.current() IS the right source: switchToNextRoundReact
                // just made the new react span current on this thread.
                parent = Span.current();
            }

            Span span = buildSpan(context, cls, parent);

            // step.id: minted for step-kind spans; inherited by chat/tool/embedding children.
            if (ts != null) {
                if ("step".equals(cls.kindAttr) && "react".equals(cls.opName)) {
                    // Only react (advisor) steps mint a new step.id and manage
                    // the stack — embedding/retrieval steps are leaf children of
                    // the enclosing react and should inherit its step.id so the
                    // UI can group them under the same react round.
                    if (ts.currentStepId != null) {
                        ts.stepIdStack.push(ts.currentStepId);
                    }
                    String stepId = ts.turnId + ":s" + ts.stepCounter.incrementAndGet();
                    span.setAttribute("gen_ai.step.id", stepId);
                    ts.currentStepId = stepId;
                    // Record this as the native round-1 react so children can
                    // reparent off it, and later rounds can detect the switch.
                    ts.activeReactSpan = span;
                    ts.activeReactScope = null;        // scope managed by Spring AI's handler flow
                    ts.activeReactIsNative = true;
                    span.setAttribute("gen_ai.react.round", ts.roundCounter.get());
                    // Fold the round number into the span name ("react round_1")
                    // per CLS GenAI Trace spec §3.3 so the trace tree shows the
                    // round instead of a bare "react".
                    span.updateName("react round_" + ts.roundCounter.get());
                } else if (ts.currentStepId != null) {
                    // chat / tool / embedding / retrieval — inherit the enclosing
                    // react's step.id so CLS can draw them as siblings of the react.
                    span.setAttribute("gen_ai.step.id", ts.currentStepId);
                    // Stamp round number so CLS can group siblings per round
                    // even in the (rare) case the user prefers the attribute view.
                    span.setAttribute("gen_ai.react.round", ts.roundCounter.get());
                }
                span.setAttribute("gen_ai.session.id", ts.sessionId);
                span.setAttribute("gen_ai.turn.id", ts.turnId);
                if (ts.userId != null) {
                    span.setAttribute("gen_ai.user.id", ts.userId);
                }
                if (ts.userName != null) {
                    span.setAttribute("gen_ai.user.name", ts.userName);
                }
            }

            // Embedding purpose heuristic (feature C) — inferred from round:
            //   - first round (gen_ai.react.round == 1) → "query" (RAG query embed)
            //   - round 2+ → "memory" (memory/validation embed before next LLM)
            // We can't use lastFinishReason as the signal because the Z-2
            // lazy switch clears it on round transition.
            if (cls.kind == CLS_EMBEDDING && ts != null) {
                String purpose = ts.roundCounter.get() == 1 ? "query" : "memory";
                span.setAttribute("gen_ai.embeddings.purpose", purpose);
            }

            // ── Tool span: capture gen_ai.tool.call.id (P0 spec requirement) ──
            // Spring AI 2.0's ToolCallingObservationContext exposes the tool
            // call id as a direct getter (getToolCallId()) — not nested inside
            // a ToolCall record as our earlier assumption. We call it reflectively
            // so the SDK stays compatible with Spring AI 1.0 (which exposes a
            // ToolCall record and uses a different accessor shape).
            String toolCallIdForScope = null;
            String toolNameForScope = null;
            if (cls.kind == CLS_TOOL) {
                String toolCallId = extractToolCallId(context);
                if (toolCallId != null) {
                    span.setAttribute("gen_ai.tool.call.id", toolCallId);
                    toolCallIdForScope = toolCallId;
                }
                // Also extract name synchronously now (same source: the
                // ToolCallingObservationContext we have in hand), so a nested
                // inner-stream subagent can read it from CLS_TOOL_INFO_KEY.
                for (KeyValue kv : context.getLowCardinalityKeyValues()) {
                    String k = kv.getKey();
                    if ("spring.ai.tool.definition.name".equals(k)
                            || "gen_ai.tool.name".equals(k)
                            || "spring.ai.tool.name".equals(k)) {
                        toolNameForScope = kv.getValue();
                        break;
                    }
                }
                if (toolNameForScope == null) {
                    Object toolDef = invokeFirst(context, "getToolDefinition");
                    if (toolDef != null) {
                        try {
                            Method m = toolDef.getClass().getMethod("name");
                            m.setAccessible(true);
                            Object v = m.invoke(toolDef);
                            if (v != null) toolNameForScope = v.toString();
                        } catch (Throwable ignored) { /* fall through */ }
                    }
                }
                // Stamp the tool name as an attribute and fold it into the span
                // name ("execute_tool getWeather") so the CLS trace tree shows
                // which tool ran instead of a bare "execute_tool".
                if (toolNameForScope != null && !toolNameForScope.isEmpty()) {
                    span.setAttribute("gen_ai.tool.name", toolNameForScope);
                    span.updateName("execute_tool " + toolNameForScope);
                }
            }

            enrichRequest(span, context, cls);

            // chat span name → "chat {model}" per CLS GenAI Trace spec §3.4.
            if (cls.kind == CLS_CHAT) {
                String model = extractChatModel(context);
                if (model != null && !model.isEmpty()) {
                    span.updateName("chat " + model);
                }
                // SDK extension: mark whether this turn is a streaming call.
                // The stream flag lives on the turn's ChatClientObservationContext
                // (isStream()); ChatModelObservationContext doesn't expose it, so
                // chat spans inherit it from their TurnState.
                if (ts != null && ts.streaming) {
                    span.setAttribute("gen_ai.request.streaming", true);
                }
            }

            // retriever span name → "retrieval {retriever_name}" per spec §3.7.
            if (cls.kind == CLS_RETRIEVAL) {
                String retriever = extractRetrieverName(context);
                if (retriever != null && !retriever.isEmpty()) {
                    span.updateName("retrieval " + retriever);
                }
            }

            Scope scope = span.makeCurrent();
            SpanState state = new SpanState(span, scope, /*entry*/ null, /*isTurn*/ false, cls);

            // Publish current tool identity on OTel Context so a nested
            // inner-stream subagent (started synchronously on the same thread
            // during the tool handler's execution) can read tool.name and
            // tool.call.id without walking the observation parent chain —
            // which is null-prone for Flux-assembly-time Observation.Contexts.
            if (cls.kind == CLS_TOOL
                    && (toolNameForScope != null || toolCallIdForScope != null)) {
                ToolInfo info = new ToolInfo(toolNameForScope, toolCallIdForScope);
                state.toolInfoScope = Context.current().with(CLS_TOOL_INFO_KEY, info).makeCurrent();
            }

            setSpanState(context, state);

            if (config.debug()) {
                LOG.debug("[cls-springai] onStart  obs={}  kind={}  spanId={}  traceId={}",
                        context.getName(), cls.kindAttr,
                        span.getSpanContext().getSpanId(),
                        span.getSpanContext().getTraceId());
            }
        } catch (Throwable t) {
            swallow("onStart[" + context.getName() + "]", t);
        }
    }

    /**
     * ChatClient start: open an entry span (SERVER, root of the trace tree)
     * wrapping an agent span (INTERNAL). Both carry session.id / turn.id.
     * The agent span is what nested advisor/tool/chat spans will parent under.
     */
    private void startChatClientTurn(Observation.Context context) {
        // ── Nested-ChatClient detection (CLS GenAI Trace v5 "invoke_subagent").
        //
        // When the host calls ChatClient.call() / .stream() from inside a tool
        // handler (sub-agent pattern), the enclosing TurnState is still live —
        // we must NOT open a second enter_application; instead we open a
        // subagent-kind agent span attached to the triggering tool.
        //
        // Four channels, in priority order (first non-null wins):
        //
        //   1. Micrometer Observation parent chain. Spring AI propagates its
        //      own observation parent via Reactor Context for most reactive
        //      hops. Works for outer=stream + inner=call (inner's Observation
        //      is built synchronously inside the tool handler, where Spring AI
        //      has restored the parent observation into the registry's current).
        //
        //   2. Reactor Context stashed by Spring AI's internal
        //      ToolCallReactiveContextHolder. This is the ONLY channel that
        //      survives outer=stream + inner=stream: inner's Observation
        //      context is built BEFORE the reactive chain subscribes, so
        //      getParentObservation() is still null, but Spring AI did
        //      snapshot the outer Reactor Context (including the outer
        //      Observation) into a ThreadLocal before invoking the tool.
        //      We reflectively read it, pull out the "micrometer.observation"
        //      key (Micrometer's ObservationThreadLocalAccessor key), and walk
        //      the observation parent chain from there to find TurnState.
        //
        //   3. OTel Context key (CLS_TURN_KEY). Works for synchronous call()
        //      including cross-thread hops done via OTel-wrapped executors.
        //
        //   4. ThreadLocal (CURRENT_TURN). Last-resort fallback for the
        //      single-thread synchronous path, where none of the above
        //      is available (e.g. unit-test harness without Observation
        //      registration, or user code that bypasses OTel entirely).
        // ── Nested subagent detection — 4-channel strategy with same-thread residue gate ──
        //
        // Channels (priority order):
        //   ① Micrometer Observation parent chain — robust for sync .call() and
        //     tool-restored .stream()→.call() flows (Spring AI restores observation
        //     parent via Reactor Context before invoking tool handler).
        //   ② Reactor Context stashed in ToolCallReactiveContextHolder — only path
        //     that survives .stream()→.stream() cross-Reactor-worker handoff.
        //   ③ OTel Context (CLS_TURN_KEY) — same-thread fallback. Can be polluted
        //     by residual scope from a previous .stream() on the same Tomcat
        //     worker (onStop runs on Reactor worker and does not pop this worker's
        //     OTel stack). Guarded below.
        //   ④ ThreadLocal (CURRENT_TURN) — last-resort fallback for broken
        //     observation chains (e.g. EmbeddingModel inside QuestionAnswerAdvisor's
        //     second RAG round, whose parent chain does not reach ChatClient).
        //     Also pollutable by same-thread residue.
        //
        // Residue gate (strategy 1-A, 2026-10-16): channels ③ and ④ are only
        // consulted when the current observation has a non-null parent. This
        // distinguishes legitimate same-thread descendants (which always have a
        // parent observation walking up to the outer ChatClient) from a fresh
        // top-level ChatClient that happens to run on a Tomcat worker where a
        // previous .stream()'s residue still sits in the thread's OTel stack and
        // ThreadLocal. Empirically zero-ambiguity: G8/G10 legitimate subagents
        // always have parentObs="spring.ai.tool"; G11 cross-request residue always
        // has parentObs=null.
        TurnState outer = findOuterTurnState(context);
        if (outer == null) outer = findOuterTurnStateFromReactorCtx();
        if (outer == null && context.getParentObservation() != null) {
            outer = Context.current().get(CLS_TURN_KEY);
            if (outer == null) outer = CURRENT_TURN.get();
        }

        if (outer != null) {
            startSubAgentTurn(context, outer);
            return;
        }
        startRootTurn(context);
    }

    /**
     * Whether this ChatClient turn is a streaming call ({@code .stream()} vs
     * {@code .call()}). Reads Spring AI's {@code ChatClientObservationContext.isStream()}
     * reflectively so the SDK keeps compiling against versions/paths that don't
     * expose it. Defaults to {@code false} when unavailable.
     */
    private static boolean isStreamContext(Observation.Context context) {
        try {
            Object v = invokeFirst(context, "isStream");
            return v instanceof Boolean && (Boolean) v;
        } catch (Throwable ignored) {
            return false;
        }
    }

    /**
     * Walk the Micrometer observation parent chain to find an enclosing
     * TurnState. Works when inner is {@code .call()}: Spring AI's
     * ToolCallingManager runs the tool handler inside a synchronous observation
     * scope, so {@link Observation.Context#getParentObservation()} resolves
     * correctly.
     *
     * <p>Does NOT work when inner is {@code .stream()}: in that case the inner
     * ChatClient's Observation.Context is built at Flux assembly time, BEFORE
     * the Reactor Context has been restored, so getParentObservation() is
     * {@code null}. See {@link #findOuterTurnStateFromReactorCtx()} for that
     * case.
     */
    private static TurnState findOuterTurnState(Observation.Context ctx) {
        Observation.Context cursor = ctx;
        while (cursor != null) {
            // Skip ctx itself; we only care about enclosing (parent) observations.
            if (cursor != ctx) {
                TurnState ts = cursor.get(TurnState.class);
                if (ts != null) return ts;
            }
            io.micrometer.observation.ObservationView parent = cursor.getParentObservation();
            if (parent == null) return null;
            io.micrometer.observation.Observation.ContextView pctx = parent.getContextView();
            cursor = (pctx instanceof Observation.Context) ? (Observation.Context) pctx : null;
        }
        return null;
    }

    /**
     * Fourth-channel detection: pull outer TurnState from the Reactor Context
     * that Spring AI's ToolCallingManager stashed into
     * {@code ToolCallReactiveContextHolder} (a ThreadLocal) before invoking
     * the tool handler.
     *
     * <p>Needed exclusively for outer=stream + inner=stream, where the inner
     * ChatClient's Observation.Context is built at Flux assembly time (before
     * the reactive chain subscribes and restores the Reactor Context onto the
     * thread), so {@link Observation.Context#getParentObservation()} is null.
     *
     * <p>Implementation notes:
     * <ul>
     *   <li>{@code ToolCallReactiveContextHolder} is in Spring AI's
     *       {@code internal} package. We access it reflectively so SDK does
     *       not take a hard compile-time dependency, and any future
     *       rename/removal gracefully falls through to the next channel.
     *   <li>Key "{@code micrometer.observation}" is Micrometer's
     *       {@code ObservationThreadLocalAccessor.KEY}; the value is the live
     *       outer {@link Observation}.
     *   <li>Entire method is wrapped in try/catch — this is a best-effort
     *       probe, never fatal.
     * </ul>
     */
    private static TurnState findOuterTurnStateFromReactorCtx() {
        try {
            Class<?> holderCls = Class.forName(
                    "org.springframework.ai.model.tool.internal.ToolCallReactiveContextHolder");
            Object cv = holderCls.getMethod("getContext").invoke(null);
            if (!(cv instanceof reactor.util.context.ContextView)) return null;
            reactor.util.context.ContextView ctxView = (reactor.util.context.ContextView) cv;
            if (!ctxView.hasKey("micrometer.observation")) return null;
            Object obsObj = ctxView.get("micrometer.observation");
            if (!(obsObj instanceof io.micrometer.observation.Observation)) return null;
            io.micrometer.observation.Observation obs = (io.micrometer.observation.Observation) obsObj;
            // The observation stashed in Reactor Context IS the outer context,
            // not its parent — so include it in the walk from the start.
            io.micrometer.observation.ObservationView cursor = obs;
            int depth = 0;
            while (cursor != null && depth < 20) {   // 20 = sanity cap
                io.micrometer.observation.Observation.ContextView pv = cursor.getContextView();
                if (pv instanceof Observation.Context) {
                    TurnState ts = ((Observation.Context) pv).get(TurnState.class);
                    if (ts != null) return ts;
                }
                cursor = pv.getParentObservation();
                depth++;
            }
        } catch (Throwable ignore) {
            // Spring AI not on classpath, class renamed/removed, or any
            // reflective failure — fall through, caller tries next channel.
        }
        return null;
    }

    /**
     * Open the outer-most ChatClient turn: an {@code enter_application} entry
     * span (SERVER, root of the trace tree) wrapping an {@code invoke_agent}
     * agent span (INTERNAL). Both carry session.id / turn.id. The agent span
     * is what nested advisor/tool/chat spans will parent under.
     */
    private void startRootTurn(Observation.Context context) {
        String sessionId = ClsBaggage.session();
        if (sessionId == null || sessionId.isEmpty()) {
            sessionId = "sess-" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        }
        String userId = ClsBaggage.user();
        String userName = ClsBaggage.userName();
        int turnNum = turnCounters
                .computeIfAbsent(sessionId, k -> new AtomicInteger(0))
                .incrementAndGet();
        String turnId = sessionId + ":t" + turnNum;

        TurnState ts = TurnState.root(sessionId, userId, userName, turnId);
        ts.streaming = isStreamContext(context);

        // ── Parent context sanitization (strategy c, 2026-10-16) ──
        // If the current OTel Context contains a CLS_TURN_KEY, it means a previous
        // .stream() on this Tomcat worker left its ctxScope on the thread's OTel
        // stack (onStop ran on a Reactor worker and did not pop this worker's
        // stack). Using it as our enter_application's parent would make this new
        // top-level turn inherit the residual trace and nest as a child span —
        // producing the "single giant trace with N nested enter_applications" CLS
        // pathology. Switch to Context.root() so this is a clean trace root.
        //
        // When CLS_TURN_KEY is absent, we honor Context.current() as-is: this is
        // either a cold start, or the host wiring provided a business parent (e.g.
        // Alibaba ARMS / OpenTelemetry javaagent injecting an http.server span).
        // Preserving that linkage is the standard "CLS trace stitches onto the
        // host's trace" behavior.
        //
        // Known trade-off (R2): if a second request on the same Tomcat worker
        // has both business parent AND CLS residue, we lose the ARMS linkage for
        // that specific request (OTel Context is immutable-layered; we cannot
        // surgically remove the CLS layer while keeping the ARMS layer). Core
        // CLS trace correctness is prioritized over best-effort APM stitching.
        Context parentCtx = Context.current();
        if (parentCtx.get(CLS_TURN_KEY) != null) {
            // Residue detected — the previous .stream()'s onStop ran on a
            // Reactor worker and left ctxScope/agentScope/entryScope on this
            // Tomcat worker's OTel stack unclosed. We cannot reclaim them
            // from here: diagnostic evidence (G11, 2026-10-16) shows the
            // true stack top at this point is Spring AI's own "chat"
            // observation Scope, not our ctxScope — so any ctxScope.close()
            // here would silently no-op (OTel LIFO). The residual Context is
            // therefore NOT reclaimable from here, and it is NOT bounded: each
            // later Scope on this thread records it as beforeAttach, and its
            // CLS_TURN_KEY entry points at the previous TurnState, which in turn
            // references its own Scopes. TurnState#release() (called at the end
            // of onStop) severs those references so the chain cannot grow. We
            // mitigate the trace-correctness aspect here by rebasing to root,
            // so the new turn's entry span is not nested under the residual ctx.
            parentCtx = Context.root();
        }

        // ── entry ────────────────────────────────────────────────────────
        Span entry = tracer.spanBuilder("enter_application")
                .setSpanKind(SpanKind.SERVER)
                .setParent(parentCtx)
                .startSpan();
        entry.setAttribute("gen_ai.span.kind", "entry");
        entry.setAttribute("gen_ai.operation.name", "enter_application");
        entry.setAttribute("gen_ai.agent.type", AGENT_TYPE);
        entry.setAttribute("gen_ai.session.id", sessionId);
        entry.setAttribute("gen_ai.turn.id", turnId);
        if (userId != null) entry.setAttribute("gen_ai.user.id", userId);
        if (userName != null) entry.setAttribute("gen_ai.user.name", userName);
        if (ts.streaming) entry.setAttribute("gen_ai.request.streaming", true);

        Scope entryScope = entry.makeCurrent();     // make entry the current parent

        // ── agent ────────────────────────────────────────────────────────
        // Span name follows the OTel GenAI convention "{operation} {detail}"
        // ("invoke_agent main") so the CLS trace tree shows the agent name
        // instead of a bare "invoke_agent".
        Span agent = tracer.spanBuilder("invoke_agent main")
                .setSpanKind(SpanKind.INTERNAL)
                .startSpan();
        agent.setAttribute("gen_ai.span.kind", "agent");
        agent.setAttribute("gen_ai.operation.name", "invoke_agent");
        agent.setAttribute("gen_ai.agent.type", AGENT_TYPE);
        // Root agent carries scope=main so subagent spans can be visually
        // distinguished in CLS UI (scope=subagent).
        agent.setAttribute("gen_ai.agent.scope", "main");
        // Spec requires both agent.type (framework identity) and agent.name
        // (agent instance name). Spring AI's ChatClient has no first-class
        // notion of "agent name", so we default to "main". Hosts that run
        // multiple ChatClients can override via ClsBaggage in a future release.
        agent.setAttribute("gen_ai.agent.name", "main");
        agent.setAttribute("gen_ai.session.id", sessionId);
        agent.setAttribute("gen_ai.turn.id", turnId);
        if (userId != null) agent.setAttribute("gen_ai.user.id", userId);
        if (userName != null) agent.setAttribute("gen_ai.user.name", userName);
        if (ts.streaming) agent.setAttribute("gen_ai.request.streaming", true);

        // Capture ChatClient request body (prompt / messages) once at start.
        String inputMsgs = extractInputMessages(context);
        if (inputMsgs != null) {
            entry.setAttribute("gen_ai.input.messages", truncate(inputMsgs, 8000));
            agent.setAttribute("gen_ai.input.messages", truncate(inputMsgs, 8000));
        }

        // Mirror Micrometer tags (model name / provider etc.).
        for (KeyValue kv : context.getLowCardinalityKeyValues()) {
            agent.setAttribute(kv.getKey(), kv.getValue());
        }

        Scope agentScope = agent.makeCurrent();     // agent becomes parent for nested obs

        ts.entrySpan = entry;
        ts.agentSpan = agent;
        ts.entryScope = entryScope;
        ts.agentScope = agentScope;

        // Attach the TurnState to the OTel Context so nested ChatClient calls
        // can detect themselves as subagents even across thread hops. The
        // Scope is closed in onStop's root teardown.
        ts.ctxScope = Context.current().with(CLS_TURN_KEY, ts).makeCurrent();

        // Store on the context so onStop can retrieve it.
        setSpanState(context, new SpanState(agent, agentScope, entry, /*isTurn*/ true,
                new Classification(CLS_AGENT, "agent", "invoke_agent")));
        context.put(TurnState.class, ts);
        CURRENT_TURN.set(ts);          // thread-local fallback (see findTurnState)

        if (config.debug()) {
            LOG.debug("[cls-springai] onStart  TURN  session={}  turn={}  traceId={}",
                    sessionId, turnId, agent.getSpanContext().getTraceId());
        }
    }

    /**
     * Open a subagent-kind agent span for a nested ChatClient call triggered
     * from inside a tool handler. Unlike {@link #startRootTurn}:
     *
     * <ul>
     *   <li>No {@code enter_application} span is opened \u2014 the whole call chain
     *       stays in <b>one trace / one entry</b> per CLS GenAI Trace v5.</li>
     *   <li>The subagent span's {@code gen_ai.operation.name} is
     *       {@code invoke_subagent}, scope is {@code subagent}, and
     *       {@code agent.name} is copied from the triggering tool (strategy 1).</li>
     *   <li>The session.id and turn.id are inherited from the outer turn \u2014
     *       subagents do not consume a new turn number.</li>
     *   <li>A fresh TurnState is still created so step/round/finish-reason
     *       state inside the subagent is isolated from the outer turn.</li>
     * </ul>
     *
     * Teardown is handled by the subagent branch of {@code onStop}.
     */
    private void startSubAgentTurn(Observation.Context context, TurnState outer) {
        String parentToolName = extractParentToolName(context);
        String parentToolCallId = extractParentToolCallId(context);

        // Fallback: in the outer-stream + inner-stream scenario, inner's
        // Observation.Context is built at Flux assembly time with no parent
        // observation, so the walks above return null. But the enclosing tool
        // observation (synchronous) has published its identity to the current
        // OTel Context under CLS_TOOL_INFO_KEY. Read it here when the primary
        // walks come up empty.
        if (parentToolName == null || parentToolCallId == null) {
            ToolInfo currentTool = Context.current().get(CLS_TOOL_INFO_KEY);
            if (currentTool != null) {
                if (parentToolName == null) parentToolName = currentTool.name;
                if (parentToolCallId == null) parentToolCallId = currentTool.callId;
            }
        }

        String agentName;
        if (parentToolName != null && !parentToolName.isEmpty()) {
            agentName = parentToolName;    // strategy 1: inherit tool name
        } else {
            // Fallback when the triggering tool cannot be reflected (older
            // Spring AI versions / broken parent chain / tool-less direct nesting).
            agentName = "subagent-" + outer.subAgentCounter.incrementAndGet();
        }

        TurnState subTs = TurnState.subAgent(outer, agentName, parentToolCallId);
        subTs.streaming = isStreamContext(context);

        // Span name follows "{operation} {agent_name}" so the trace tree shows
        // which subagent this is (e.g. "invoke_subagent getWeather").
        Span subAgent = tracer.spanBuilder("invoke_subagent " + agentName)
                .setSpanKind(SpanKind.INTERNAL)
                .setParent(Context.current())   // naturally parents under the triggering tool span
                .startSpan();
        subAgent.setAttribute("gen_ai.span.kind", "agent");
        subAgent.setAttribute("gen_ai.operation.name", "invoke_subagent");
        subAgent.setAttribute("gen_ai.agent.type", AGENT_TYPE);
        subAgent.setAttribute("gen_ai.agent.scope", "subagent");
        subAgent.setAttribute("gen_ai.agent.name", agentName);
        // Reuse outer session.id + turn.id: one trace, one turn, N subagents.
        subAgent.setAttribute("gen_ai.session.id", outer.sessionId);
        subAgent.setAttribute("gen_ai.turn.id", outer.turnId);
        if (outer.userId != null) subAgent.setAttribute("gen_ai.user.id", outer.userId);
        if (outer.userName != null) subAgent.setAttribute("gen_ai.user.name", outer.userName);
        if (parentToolCallId != null) {
            subAgent.setAttribute("gen_ai.subagent.parent_tool_call.id", parentToolCallId);
        }
        if (subTs.streaming) subAgent.setAttribute("gen_ai.request.streaming", true);

        // Capture subagent's own input prompt (nested ChatClient's user message).
        String inputMsgs = extractInputMessages(context);
        if (inputMsgs != null) {
            subAgent.setAttribute("gen_ai.input.messages", truncate(inputMsgs, 8000));
        }

        for (KeyValue kv : context.getLowCardinalityKeyValues()) {
            subAgent.setAttribute(kv.getKey(), kv.getValue());
        }

        Scope subScope = subAgent.makeCurrent();

        subTs.agentSpan = subAgent;    // entrySpan stays null for subagents
        subTs.agentScope = subScope;
        subTs.ctxScope = Context.current().with(CLS_TURN_KEY, subTs).makeCurrent();

        setSpanState(context, new SpanState(subAgent, subScope, /*entrySpan*/ null,
                /*isTurn*/ true, /*isSubAgent*/ true,
                new Classification(CLS_AGENT, "agent", "invoke_subagent")));
        context.put(TurnState.class, subTs);
        CURRENT_TURN.set(subTs);       // temporarily override; onStop restores outer

        if (config.debug()) {
            LOG.debug("[cls-springai] onStart  SUBAGENT  session={}  turn={}  name={}  parent_tool={}",
                    outer.sessionId, outer.turnId, agentName,
                    parentToolCallId == null ? "<none>" : parentToolCallId);
        }
    }

    /**
     * Walk up the Micrometer observation parent chain to find the enclosing
     * {@code spring.ai.tool} (or {@code gen_ai.tool.call}) observation and
     * read its tool name from the low-cardinality tags Spring AI stamps on
     * every tool observation. Returns null when the parent chain contains no
     * tool \u2014 e.g. a user directly starts a nested ChatClient without routing
     * through a tool (uncommon but legal).
     */
    private static String extractParentToolName(Observation.Context context) {
        Observation.Context cursor = parentContext(context);
        while (cursor != null) {
            String n = cursor.getName();
            if (n != null && (n.contains(".tool") || n.equals("gen_ai.tool.call"))) {
                for (KeyValue kv : cursor.getLowCardinalityKeyValues()) {
                    String k = kv.getKey();
                    // Spring AI 2.0 actually stamps this as
                    // "spring.ai.tool.definition.name"; also tolerate the
                    // generic "gen_ai.tool.name" / "spring.ai.tool.name".
                    if ("spring.ai.tool.definition.name".equals(k)
                            || "gen_ai.tool.name".equals(k)
                            || "spring.ai.tool.name".equals(k)) {
                        return kv.getValue();
                    }
                }
                // Reflective fallback: ToolCallingObservationContext.getToolDefinition()
                // returns a ToolDefinition (record) \u2014 we need its .name(), not its
                // toString() (which prints the full record payload incl. schema).
                Object toolDef = invokeFirst(cursor, "getToolDefinition");
                if (toolDef != null) {
                    try {
                        Method m = toolDef.getClass().getMethod("name");
                        m.setAccessible(true);
                        Object v = m.invoke(toolDef);
                        if (v != null) return v.toString();
                    } catch (Throwable ignored) { /* fall through */ }
                }
                return null;
            }
            cursor = parentContext(cursor);
        }
        return null;
    }

    /** Same walk as {@link #extractParentToolName}, but returns the enclosing
     *  tool's {@code gen_ai.tool.call.id} \u2014 the spec-mandated attribute that
     *  links the subagent back to its triggering tool call. */
    private static String extractParentToolCallId(Observation.Context context) {
        Observation.Context cursor = parentContext(context);
        while (cursor != null) {
            String n = cursor.getName();
            if (n != null && (n.contains(".tool") || n.equals("gen_ai.tool.call"))) {
                // Spring AI 2.0 stamps this on HighCardinality as
                // "spring.ai.tool.call.id"; tolerate the generic
                // "gen_ai.tool.call.id" too for forward-compat.
                for (KeyValue kv : cursor.getHighCardinalityKeyValues()) {
                    String k = kv.getKey();
                    if ("spring.ai.tool.call.id".equals(k)
                            || "gen_ai.tool.call.id".equals(k)) {
                        return kv.getValue();
                    }
                }
                for (KeyValue kv : cursor.getLowCardinalityKeyValues()) {
                    if ("gen_ai.tool.call.id".equals(kv.getKey())) {
                        return kv.getValue();
                    }
                }
                // Reflective fallback: ToolCallingObservationContext exposes
                // getToolCallId() directly.
                String id = invokeString(cursor, "getToolCallId");
                if (id != null && !id.isEmpty()) return id;
                return null;
            }
            cursor = parentContext(cursor);
        }
        return null;
    }

    @Override
    public void onEvent(Observation.Event event, Observation.Context context) {
        try {
            SpanState s = getSpanState(context);
            if (s != null) s.span.addEvent(event.getName());
        } catch (Throwable t) {
            swallow("onEvent", t);
        }
    }

    @Override
    public void onError(Observation.Context context) {
        try {
            SpanState s = getSpanState(context);
            if (s == null) return;
            Throwable err = context.getError();
            if (err != null) {
                s.span.recordException(err);
                s.span.setStatus(StatusCode.ERROR, err.getClass().getSimpleName()
                        + ": " + String.valueOf(err.getMessage()));
                if ("tool".equals(s.classification.kindAttr)) {
                    s.span.setAttribute("gen_ai.tool.error.type", "execution_error");
                }
                if (s.entrySpan != null) {
                    s.entrySpan.recordException(err);
                    s.entrySpan.setStatus(StatusCode.ERROR);
                }
            } else {
                s.span.setStatus(StatusCode.ERROR);
            }
        } catch (Throwable t) {
            swallow("onError", t);
        }
    }

    @Override
    public void onStop(Observation.Context context) {
        SpanState s = getSpanState(context);
        if (s == null) return;
        // Collapsed observation (nested advisor in Spring AI 2.0): nothing to
        // close — we never built a span or pushed a scope for it in onStart.
        if (s.collapsed) {
            if (config.debug()) {
                LOG.debug("[cls-springai] onStop   obs={}  kind={}  COLLAPSED (no-op)",
                        context.getName(), s.classification.kindAttr);
            }
            return;
        }
        try {
            Classification cls = s.classification;

            // Mirror post-call tags (gen_ai.response.* / usage.* are typically
            // only populated by Spring AI after the underlying call returns).
            for (KeyValue kv : context.getLowCardinalityKeyValues()) {
                s.span.setAttribute(kv.getKey(), kv.getValue());
            }
            for (KeyValue kv : context.getHighCardinalityKeyValues()) {
                s.span.setAttribute(kv.getKey(), kv.getValue());
            }

            // Pull completion / tool result from the ObservationContext itself.
            enrichResponse(s.span, context, cls);

            // ── duration_ms (P1 spec) ──────────────────────────────────────
            // Leaf-span-level duration in milliseconds, under spec-defined keys
            // (gen_ai.chat.duration_ms / gen_ai.tool.call.duration_ms /
            // gen_ai.retrieval.duration_ms). We compute it from the nanoTime
            // snapshot taken at onStart rather than reading the OTel span's
            // own duration (which isn't observable until end()). This gives
            // CLS a usage-queryable scalar even when the span itself hasn't
            // been exported yet (e.g. for RED metric panels).
            if (s.startNanos != 0L) {
                long durationMs = (System.nanoTime() - s.startNanos) / 1_000_000L;
                switch (cls.kind) {
                    case CLS_CHAT:
                        s.span.setAttribute("gen_ai.chat.duration_ms", durationMs);
                        break;
                    case CLS_TOOL:
                        s.span.setAttribute("gen_ai.tool.call.duration_ms", durationMs);
                        break;
                    case CLS_RETRIEVAL:
                        s.span.setAttribute("gen_ai.retrieval.duration_ms", durationMs);
                        break;
                    default: /* no duration_ms for step / agent / embedding — not in spec */
                }
            }

            // ── Record chat.finishReason for Z-2 lazy round switch ──────────
            // We must capture this *after* enrichResponse (which also reads the
            // response reflectively) so lastChatResponse is populated. The spec
            // mandates finish_reason*s* (plural, array) on chat spans; even if
            // Spring AI only exposes one reason per response we still emit it as
            // a single-element JSON array so the CLS schema matches.
            if (cls.kind == CLS_CHAT) {
                TurnState ts = findTurnState(context);
                if (ts != null) {
                    String reason = extractFinishReason(context, ts);
                    if (reason != null) {
                        ts.lastFinishReason = reason;
                        s.span.setAttribute("gen_ai.response.finish_reasons",
                                "[\"" + reason + "\"]");
                    }
                    if (config.debug()) {
                        LOG.debug("[cls-springai] chat.onStop  finish_reason={}  (lastFinishReason now={})",
                                reason, ts.lastFinishReason);
                    }
                }
            }

            // ChatClient (turn) finalisation: write output messages on both
            // agent and entry, then close agent + entry together. Status is
            // conveyed via OTel span statusCode (OK / ERROR), not via custom
            // gen_ai.turn.completed — the spec expresses success/failure through
            // standard OTLP StatusCode.
            if (s.isTurn) {
                String out = extractOutputMessages(context);
                if (out != null) {
                    s.span.setAttribute("gen_ai.output.messages", truncate(out, 8000));
                    if (s.entrySpan != null) {
                        s.entrySpan.setAttribute("gen_ai.output.messages", truncate(out, 8000));
                    }
                }
                // Explicit OK status for successful turns (ERROR already set in onError).
                if (context.getError() == null) {
                    s.span.setStatus(StatusCode.OK);
                    if (s.entrySpan != null) s.entrySpan.setStatus(StatusCode.OK);
                }
            }
        } catch (Throwable t) {
            swallow("onStop enrichment", t);
        } finally {
            // When a react step ends, restore the previous step.id so that any
            // subsequent chat/tool emitted by sibling reacts inherits the right
            // step chain. Does nothing when the stack is empty (outermost react).
            // Note: only react pops the stack — embedding/retrieval leaf steps
            // never pushed in the first place (see onStart logic).
            boolean isReact = "step".equals(s.classification.kindAttr)
                    && "react".equals(s.classification.opName);
            TurnState ts = isReact || s.isTurn ? findTurnState(context) : null;
            if (isReact && ts != null) {
                // Spec-required: stamp finish_reason on the react step span
                // itself (not just on the inner chat). Reflects the outcome of
                // this ReAct round: tool_calls (another round follows), stop
                // (final answer), length / content_filter / error, etc.
                String rr = ts.lastFinishReason != null ? ts.lastFinishReason : "stop";
                s.span.setAttribute("gen_ai.react.finish_reason", rr);
                ts.currentStepId = ts.stepIdStack.isEmpty() ? null : ts.stepIdStack.pop();
            }

            // ── Z-2 native-react premature-end handling ───────────────────
            // If round 1 ended with tool_calls and round 2+ opened, we already
            // called s.span.end() on the native react back in
            // switchToNextRoundReact() to stop its duration. Skip the second
            // end() here, but still close the scope Spring AI pushed for us.
            if (isReact && ts != null && ts.nativeReactPrematurelyEnded) {
                // Close tool-info scope BEFORE span scope (LIFO). Null for
                // non-tool spans, so this is a no-op for react premature-end.
                try { if (s.toolInfoScope != null) s.toolInfoScope.close(); }
                catch (Throwable t) { swallow("react premature-end toolInfoScope.close", t); }
                try { s.scope.close(); } catch (Throwable t) { swallow("react premature-end scope.close", t); }
                ts.nativeReactPrematurelyEnded = false;
                // Clear the active-react state only if it's still pointing at
                // the native span (not at a round-2 synthetic one).
                if (ts.activeReactIsNative) {
                    ts.activeReactSpan = null;
                }
                if (config.debug()) {
                    LOG.debug("[cls-springai] onStop   obs={}  kind={}  (native react premature-end acknowledged)",
                            context.getName(), s.classification.kindAttr);
                }
                return;
            }

            // Order matters: close tool-info scope → (for non-turn obs only) close
            // inner scope + end inner span. For turn observations (root + subagent)
            // the inner scope (= agentScope) is one of 2–3 interrelated OTel scopes
            // (entry/agent/ctx) that must close together in strict LIFO; those are
            // handled below by the per-turn teardown branches, which own the full
            // lifecycle. Mis-ordered closes silently no-op in OTel's
            // ArrayBasedContextStorage and leak the stack to subsequent
            // observations on the thread — that is the bug this split fixes.
            try { if (s.toolInfoScope != null) s.toolInfoScope.close(); }
            catch (Throwable t) { swallow("toolInfoScope.close", t); }
            if (!s.isTurn) {
                try { s.scope.close(); }
                catch (Throwable t) { swallow("obs scope.close", t); }
                try { s.span.end();   } catch (Throwable t) { swallow("obs span.end", t); }
            }

            // ── Z-2 synthetic-react cleanup on turn end ───────────────────
            // If the final round used an SDK-synthesised react (round 2+ with
            // finish_reason=stop), the span is still open because no Spring AI
            // observation naturally closes it. Close it here as part of the
            // ChatClient turn teardown.
            if (s.isTurn && ts != null && !ts.activeReactIsNative && ts.activeReactSpan != null) {
                try { if (ts.activeReactScope != null) ts.activeReactScope.close(); } catch (Throwable t) { swallow("synthetic react activeReactScope.close", t); }
                try { ts.activeReactSpan.end(); } catch (Throwable t) { swallow("synthetic react activeReactSpan.end", t); }
                ts.activeReactScope = null;
                ts.activeReactSpan = null;
            }

            if (s.isTurn) {
                TurnState ts2 = ts != null ? ts : context.get(TurnState.class);
                if (s.isSubAgent) {
                    // Subagent teardown — close scopes in strict LIFO order:
                    //   (C) ctxScope — last opened in startSubAgentTurn, top of stack
                    //   (B) agentScope — opened just before ctxScope
                    // Subagents have no entryScope (entrySpan is null; the entry is
                    // shared with the outer root turn). After both scopes are
                    // popped the thread's OTel current span returns to the outer
                    // TURN's agentScope automatically, so nested-subagent detection
                    // on subsequent observations finds the outer TurnState again.
                    if (ts2 != null) {
                        try { if (ts2.ctxScope != null) ts2.ctxScope.close(); }
                        catch (Throwable t) { swallow("subagent ctxScope.close", t); }
                    }
                    // (B) agentScope + agentSpan — moved out of the generic block
                    // above (which now skips isTurn), so this branch owns the
                    // full subagent scope/span lifecycle.
                    try { s.scope.close(); }
                    catch (Throwable t) { swallow("subagent agentScope.close", t); }
                    try { s.span.end(); } catch (Throwable t) { swallow("subagent agentSpan.end", t); }
                    // Restore the outer TurnState as the current one — Context
                    // propagation handles this automatically, but ThreadLocal
                    // needs an explicit set() when we're on the same thread.
                    if (ts2 != null && ts2.outer != null) {
                        CURRENT_TURN.set(ts2.outer);
                    } else {
                        CURRENT_TURN.remove();
                    }
                    if (config.debug()) {
                        LOG.debug("[cls-springai] onStop   SUBAGENT  name={}",
                                ts2 == null ? "<null>" : ts2.agentName);
                    }
                } else {
                    // Root turn teardown — close scopes in strict LIFO order:
                    //   (C) ctxScope   — last opened in startRootTurn, top of stack
                    //   (B) agentScope — opened just before ctxScope
                    //   (A) entryScope — first opened, bottom
                    // Each span is ended immediately after its scope's close, so
                    // the span lifecycle stays glued to its scope lifecycle while
                    // the stack unwinds in the order OTel expects.
                    if (ts2 != null) {
                        // (C) ctxScope — top of stack, must close first
                        try { if (ts2.ctxScope != null) ts2.ctxScope.close(); }
                        catch (Throwable t) { swallow("root ctxScope.close", t); }
                        // (B) agentScope + agentSpan — moved out of the generic
                        // block (which now skips isTurn), so this branch owns the
                        // full root-turn scope/span lifecycle.
                        try { s.scope.close(); }
                        catch (Throwable t) { swallow("root agentScope.close", t); }
                        try { s.span.end(); } catch (Throwable t) { swallow("root agentSpan.end", t); }
                        // (A) entryScope + entrySpan — bottom of stack, last to close
                        try { if (ts2.entryScope != null) ts2.entryScope.close(); }
                        catch (Throwable t) { swallow("root entryScope.close", t); }
                        try { if (ts2.entrySpan != null) ts2.entrySpan.end(); } catch (Throwable t) { swallow("root entrySpan.end", t); }
                    }
                    // Clear the thread-local pointer so the next request on this
                    // thread starts fresh (critical for Tomcat NIO exec threads
                    // which are heavily reused).
                    CURRENT_TURN.remove();
                }
                // Break the TurnState -> Scope/Span/outer references so a residual
                // OTel Context left on the originating thread (cross-thread stream
                // teardown) cannot chain successive turns together. See
                // TurnState#release.
                if (ts2 != null) ts2.release();
            }
            if (config.debug()) {
                LOG.debug("[cls-springai] onStop   obs={}  kind={}",
                        context.getName(), s.classification.kindAttr);
            }
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    //  Z-2 round switching (feature B)
    // ═════════════════════════════════════════════════════════════════════

    /**
     * End the current round's react span and open a fresh SDK-synthesised one.
     * Called from chat.onStart when the previous chat's finish_reason was
     * tool_calls, so the new chat belongs to the next ReAct round.
     *
     * <p>Two cases:
     * <ul>
     *   <li>Current react is Spring AI's native advisor span (round 1): we
     *       call {@link Span#end()} early to stop its duration, but leave the
     *       scope alone — Spring AI's handler owns it. The flag
     *       {@link TurnState#nativeReactPrematurelyEnded} tells our later
     *       advisor.onStop to skip double-ending.</li>
     *   <li>Current react is already an SDK-synthesised one (round N≥2 ending,
     *       round N+1 starting): close its scope and end its span normally.</li>
     * </ul>
     *
     * <p>The new react carries a fresh step.id (shared by its children) and a
     * monotonically-increasing gen_ai.round attribute.
     */
    private void switchToNextRoundReact(TurnState ts) {
        try {
            // 0. Stamp the finish_reason on the react we are about to close.
            //    This is the only moment where lastFinishReason=="tool_calls"
            //    is still fresh (switchToNextRoundReact's caller only fires
            //    when lastFinishReason=="tool_calls"), so write it before the
            //    span is end()'ed below. Spec-required on step spans.
            if (ts.activeReactSpan != null && ts.lastFinishReason != null) {
                try {
                    ts.activeReactSpan.setAttribute("gen_ai.react.finish_reason",
                            ts.lastFinishReason);
                } catch (Throwable t) { swallow("react finish_reason stamp", t); }
            }

            // 1. End the current react.
            if (ts.activeReactIsNative) {
                if (ts.activeReactSpan != null) {
                    try { ts.activeReactSpan.end(); } catch (Throwable t) { swallow("native react end", t); }
                    ts.nativeReactPrematurelyEnded = true;
                }
            } else {
                if (ts.activeReactScope != null) {
                    try { ts.activeReactScope.close(); } catch (Throwable t) { swallow("react scope.close", t); }
                }
                if (ts.activeReactSpan != null) {
                    try { ts.activeReactSpan.end(); } catch (Throwable t) { swallow("react span.end", t); }
                }
            }

            // 2. Open the new synthetic react, parented at the agent span so
            //    rounds are drawn as siblings on CLS (per design form ①).
            Span parent = ts.agentSpan != null ? ts.agentSpan : Span.current();
            io.opentelemetry.context.Context parentCtx = io.opentelemetry.context.Context.current()
                    .with(parent);
            Span newReact = tracer.spanBuilder("react")
                    .setSpanKind(SpanKind.INTERNAL)
                    .setParent(parentCtx)
                    .startSpan();
            int round = ts.roundCounter.incrementAndGet();
            // Fold the round number into the span name ("react round_2") per CLS
            // GenAI Trace spec §3.3, matching the native round-1 react.
            newReact.updateName("react round_" + round);
            String stepId = ts.turnId + ":s" + ts.stepCounter.incrementAndGet();
            newReact.setAttribute("gen_ai.span.kind", "step");
            newReact.setAttribute("gen_ai.operation.name", "react");
            newReact.setAttribute("gen_ai.agent.type", AGENT_TYPE);
            newReact.setAttribute("gen_ai.session.id", ts.sessionId);
            newReact.setAttribute("gen_ai.turn.id", ts.turnId);
            newReact.setAttribute("gen_ai.step.id", stepId);
            newReact.setAttribute("gen_ai.react.round", round);
            if (ts.userId != null) newReact.setAttribute("gen_ai.user.id", ts.userId);
            if (ts.userName != null) newReact.setAttribute("gen_ai.user.name", ts.userName);

            Scope newScope = newReact.makeCurrent();

            ts.activeReactSpan = newReact;
            ts.activeReactScope = newScope;
            ts.activeReactIsNative = false;
            ts.currentStepId = stepId;
            ts.lastFinishReason = null;         // consumed; wait for next chat.onStop

            if (config.debug()) {
                LOG.debug("[cls-springai] round-switch  turn={}  round={}  stepId={}  spanId={}",
                        ts.turnId, round, stepId, newReact.getSpanContext().getSpanId());
            }
        } catch (Throwable t) {
            swallow("round switch", t);
        }
    }

    /**
     * Read {@code finish_reason} off the chat observation. First tries the
     * cached ChatResponse on {@link TurnState} (populated by
     * {@code enrichResponse}), then falls back to a direct reflection pass.
     * Returns lowercase for stable comparison.
     */
    private static String extractFinishReason(Observation.Context context, TurnState ts) {
        try {
            org.springframework.ai.chat.model.ChatResponse cr = ts.lastChatResponse;
            // enrichResponse may have populated ts.lastChatResponse already for
            // this very span; but to be safe, re-read from the context too.
            if (cr == null) {
                Object resp = invokeFirst(context, "getResponse", "getResult");
                if (resp instanceof org.springframework.ai.chat.model.ChatResponse) {
                    cr = (org.springframework.ai.chat.model.ChatResponse) resp;
                }
            }
            if (cr == null) return null;
            org.springframework.ai.chat.model.Generation g = cr.getResult();
            if (g == null || g.getMetadata() == null) return null;
            String r = g.getMetadata().getFinishReason();
            return r == null ? null : r.toLowerCase(java.util.Locale.ROOT);
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * Reflectively read the {@code tool_call.id} off a Spring AI tool
     * observation context. The spec requires this on every tool span so the
     * span can be joined back to the chat response's {@code tool_calls[].id}
     * that triggered the invocation.
     *
     * <p>Spring AI 2.0's {@code ToolCallingObservationContext} exposes the id
     * directly via {@code getToolCallId(): String}. Spring AI 1.0 exposes a
     * {@code getToolCall()} returning a record with {@code id()}. We try the
     * flat accessor first (2.0+), then fall back to the record form (1.0).
     * Returns {@code null} if neither shape matches (e.g. non-Spring-AI
     * frameworks emitting a bare {@code Observation.Context}).
     */
    private static String extractToolCallId(Observation.Context context) {
        try {
            // Spring AI 2.0+: direct String getter.
            Object direct = invokeFirst(context, "getToolCallId");
            if (direct instanceof String) {
                String s = (String) direct;
                if (!s.isEmpty()) return s;
            }
            // Spring AI 1.0: nested record.
            Object toolCall = invokeFirst(context, "getToolCall");
            if (toolCall == null) return null;
            for (String getter : new String[] { "id", "getId", "callId", "getCallId" }) {
                try {
                    Method m = toolCall.getClass().getMethod(getter);
                    m.setAccessible(true);
                    Object v = m.invoke(toolCall);
                    if (v != null) {
                        String s = v.toString();
                        if (!s.isEmpty()) return s;
                    }
                } catch (NoSuchMethodException ignored) { /* try next */ }
            }
        } catch (Throwable ignored) { /* fall through to null */ }
        return null;
    }

    // ══════════════════════════════════════════════════════════════════════
    //  Classification: observation.name  →  CLS span kind + operation.name
    // ══════════════════════════════════════════════════════════════════════

    private static final int CLS_ENTRY_WRAPPER = 0;  // special: open entry+agent
    private static final int CLS_AGENT = 1;
    private static final int CLS_STEP  = 2;
    private static final int CLS_CHAT  = 3;
    private static final int CLS_TOOL  = 4;
    /** VectorStore QUERY observation → CLS 'retriever' kind (P1 spec).
     *  ADD / DELETE operations stay on CLS_STEP (they're not retrievals). */
    private static final int CLS_RETRIEVAL = 6;
    /** EmbeddingModel calls — Spring AI 2.0 emits them under the same obs name
     *  (gen_ai.client.operation) as ChatModel, so they must be distinguished by
     *  the Context subclass type, not by observation name. See
     *  {@link #classify(Observation.Context)}. */
    private static final int CLS_EMBEDDING = 5;

    /** Tri-tuple describing how to render a Spring AI observation in CLS terms. */
    private static final class Classification {
        final int kind;         // one of CLS_*
        final String kindAttr;  // value written to gen_ai.span.kind
        final String opName;    // value written to gen_ai.operation.name
        Classification(int kind, String kindAttr, String opName) {
            this.kind = kind; this.kindAttr = kindAttr; this.opName = opName;
        }
    }

    private static Classification classify(Observation.Context context) {
        String name = context == null ? null : context.getName();
        if (name == null) return new Classification(CLS_STEP, "step", "unknown");
        // ChatClient top-level call → open entry+agent pair.
        if (name.equals("spring.ai.chat.client")) {
            return new Classification(CLS_ENTRY_WRAPPER, "agent", "invoke_agent");
        }
        if (name.contains(".advisor")) {
            return new Classification(CLS_STEP, "step", "react");
        }
        // Spring AI 2.0 collapsed both ChatModel and EmbeddingModel onto the
        // same observation name (gen_ai.client.operation). Disambiguate by the
        // Context subclass's fully-qualified name — cheapest reliable signal.
        if (name.contains(".chat.model") || name.equals("gen_ai.client.operation")) {
            String ctxCls = context.getClass().getName();
            if (ctxCls.contains(".embedding.")) {
                return new Classification(CLS_EMBEDDING, "step", "embedding");
            }
            if (ctxCls.contains(".image.")) {
                // Image generation — render as a generic step for now so the span
                // isn't misreported as chat. Dedicated handling can come later.
                return new Classification(CLS_STEP, "step", "image_generation");
            }
            return new Classification(CLS_CHAT, "chat", "chat");
        }
        if (name.contains(".tool") || name.equals("gen_ai.tool.call")) {
            return new Classification(CLS_TOOL, "tool", "execute_tool");
        }
        if (name.contains(".embedding")) {
            return new Classification(CLS_EMBEDDING, "step", "embedding");
        }
        if (name.contains(".vector.store") || name.equals("db.vector.client.operation")) {
            // Only QUERY-shaped vector-store ops carry retrieval semantics
            // (there IS a query_text + a response document list). ADD / DELETE
            // mutate the store — they're not 'retrieval' in the GenAI sense
            // and the CLS 'retriever' schema would be a bad fit (no query_text,
            // no response documents). Keep those on step kind for now.
            String vsOp = extractVectorStoreOperation(context);
            if (vsOp == null || "query".equalsIgnoreCase(vsOp)) {
                return new Classification(CLS_RETRIEVAL, "retriever", "retrieval");
            }
            // Normalise ADD/DELETE op names for the span: 'add_documents' /
            // 'delete_documents' is spec-consistent with retriever's
            // 'retrieval' / rerank's 'rerank_documents' naming.
            return new Classification(CLS_STEP, "step",
                    vsOp.toLowerCase() + "_documents");
        }
        return new Classification(CLS_STEP, "step", name);
    }

    /**
     * Best-effort retriever name for the {@code "retrieval {retriever_name}"}
     * span name (spec §3.7). Prefers the vector-store/db provider carried as a
     * Micrometer tag, then falls back to the Observation.Context's class simple
     * name with the trailing "ObservationContext" trimmed (e.g.
     * {@code VectorStoreObservationContext} → {@code VectorStore}). Returns
     * {@code null} if nothing meaningful can be resolved (span keeps bare
     * "retrieval").
     */
    private static String extractRetrieverName(Observation.Context context) {
        String tag = findTagValue(context,
                "db.system", "db.system.name", "gen_ai.system",
                "spring.ai.vector.store.provider", "db.vector.store.provider");
        if (tag != null && !tag.isEmpty()) return tag;
        String cn = context.getClass().getSimpleName();
        if (cn != null && !cn.isEmpty()) {
            if (cn.endsWith("ObservationContext")) {
                cn = cn.substring(0, cn.length() - "ObservationContext".length());
            }
            if (!cn.isEmpty()) return cn;
        }
        return null;
    }

    /**
     * Reflectively read the VectorStore operation (QUERY / ADD / DELETE) off a
     * Spring AI 2.0 {@code VectorStoreObservationContext}. Returns {@code null}
     * if the context doesn't expose it (older Spring AI versions, or a plain
     * {@link Observation.Context}) so the caller can fall back to retriever.
     */
    /**
     * Best-effort chat model name for the {@code "chat {model}"} span name.
     * Reads the Micrometer tag first (Spring AI sets {@code gen_ai.request.model}
     * as a low-cardinality key on ChatModel observations), then falls back to
     * the request Prompt's {@link org.springframework.ai.chat.prompt.ChatOptions}.
     * Returns {@code null} if no model can be resolved (span keeps bare "chat").
     */
    private static String extractChatModel(Observation.Context context) {
        String model = findTagValue(context, "gen_ai.request.model", "gen_ai.response.model");
        if (model != null && !model.isEmpty()) return model;
        try {
            Object req = invokeRequest(context, "getRequest", "getRequestOptions", "getPrompt");
            Object opts = (req instanceof org.springframework.ai.chat.prompt.Prompt)
                    ? ((org.springframework.ai.chat.prompt.Prompt) req).getOptions()
                    : req;   // getRequestOptions may already return ChatOptions
            if (opts instanceof org.springframework.ai.chat.prompt.ChatOptions) {
                return ((org.springframework.ai.chat.prompt.ChatOptions) opts).getModel();
            }
        } catch (Throwable ignored) { /* best-effort */ }
        return null;
    }

    private static String extractVectorStoreOperation(Observation.Context context) {
        try {
            Object op = invokeFirst(context, "getOperationName");
            return op == null ? null : op.toString();
        } catch (Throwable ignored) {
            return null;
        }
    }

    /**
     * Resolve the OTel parent {@link Span} by walking the Micrometer
     * parent-{@link io.micrometer.observation.Observation} chain and reusing
     * the first ancestor whose {@link SpanState} we already own.
     *
     * <p>Why this exists: Reactor's automatic context propagation
     * ({@link reactor.core.publisher.Hooks#enableAutomaticContextPropagation})
     * only carries thread-locals that have a registered
     * {@code io.micrometer.context.ThreadLocalAccessor}. Micrometer's
     * {@code ObservationThreadLocalAccessor} is registered via SPI, so
     * Spring AI's inner observations (chat / retriever / embedding /
     * advisor) correctly see their parent observation on BoundedElastic
     * workers. OTel's {@code io.opentelemetry.context.Context}, however,
     * has no analogous accessor registered — {@link Span#current()} on a
     * Reactor worker therefore returns {@link Span#getInvalid()} and
     * every child span becomes a new trace root.
     *
     * <p>Fix: instead of relying on OTel's thread-local, read our own
     * {@link SpanState} from the parent observation's {@code ContextView}.
     * This gives us the exact OTel span the parent observation produced,
     * regardless of which thread we're on. If the chain's root is a
     * foreign observation we never saw (e.g. a Spring MVC server
     * observation), we fall back to {@link Span#current()}, matching the
     * pre-fix behaviour for non-reactive paths.
     *
     * <p>Scope: this is only called from the "regular observation" branch
     * of {@code onStart}. TURN (top-level agent) and SUBAGENT (nested
     * ChatClient) have their own well-tested parent-resolution paths
     * ({@link #startChatClientTurn}) that this method does NOT replace.
     *
     * @return a {@link Span} to use as OTel parent — valid span if an
     *         ancestor was found, otherwise {@link Span#current()}.
     */
    private Span resolveParentSpanFromObservation(Observation.Context context) {
        try {
            io.micrometer.observation.ObservationView ancestor = context.getParentObservation();
            while (ancestor != null) {
                SpanState state = ancestor.getContextView().get(SPAN_STATE_KEY);
                if (state != null && state.span != null
                        && state.span.getSpanContext().isValid()) {
                    return state.span;
                }
                ancestor = ancestor.getContextView().getParentObservation();
            }
        } catch (Throwable t) {
            // Defensive: never let parent lookup break onStart. Fall through
            // to the pre-fix behaviour (Span.current()) which is still correct
            // for non-reactive paths.
            if (config.debug()) {
                LOG.debug("[cls-springai] parent-observation lookup failed; "
                        + "falling back to Span.current(): {}", t.toString());
            }
        }
        return Span.current();
    }

    private Span buildSpan(Observation.Context context, Classification cls, Span parent) {
        SpanKind otelKind;
        switch (cls.kind) {
            case CLS_CHAT:
            case CLS_EMBEDDING:
            case CLS_TOOL:  otelKind = SpanKind.CLIENT;   break;
            // retriever is spec'd as INTERNAL (data access, not a client call).
            case CLS_RETRIEVAL:
            case CLS_AGENT: otelKind = SpanKind.INTERNAL; break;
            default:        otelKind = SpanKind.INTERNAL;
        }
        SpanBuilder b = tracer.spanBuilder(cls.opName).setSpanKind(otelKind);
        // Explicitly parent under the resolved span from the Micrometer
        // observation chain. Without this, OTel falls back to Span.current()
        // which is INVALID on Reactor worker threads — producing a fresh trace
        // id per span. We only setParent when we actually resolved a valid
        // ancestor; otherwise let OTel use Context.current() as before (which
        // on the originating thread is still correct for non-reactive paths).
        if (parent != null && parent.getSpanContext().isValid()) {
            b.setParent(Context.current().with(parent));
        }
        Span span = b.startSpan();
        span.setAttribute("gen_ai.span.kind", cls.kindAttr);
        span.setAttribute("gen_ai.operation.name", cls.opName);
        span.setAttribute("gen_ai.agent.type", AGENT_TYPE);

        for (KeyValue kv : context.getLowCardinalityKeyValues()) {
            span.setAttribute(kv.getKey(), kv.getValue());
        }
        for (KeyValue kv : context.getHighCardinalityKeyValues()) {
            span.setAttribute(kv.getKey(), kv.getValue());
        }
        // User-supplied custom attributes written on every span under the
        // 'agent.custom.' prefix per CLS GenAI Trace v5 extension spec.
        // The map is pre-resolved from builder args + env vars + .env file in
        // ClsConfig, so we just stamp it verbatim here — cheap and immutable.
        java.util.Map<String, String> custom = config.customAttributes();
        if (custom != null && !custom.isEmpty()) {
            for (java.util.Map.Entry<String, String> e : custom.entrySet()) {
                span.setAttribute("agent.custom." + e.getKey(), e.getValue());
            }
        }
        return span;
    }

    // ══════════════════════════════════════════════════════════════════════
    //  Reflective enrichment (prompt / completion / tool arguments / result)
    // ══════════════════════════════════════════════════════════════════════

    private void enrichRequest(Span span, Observation.Context context, Classification cls) {
        Object req = invokeRequest(context,
                "getRequest", "getRequestOptions", "getPrompt");
        switch (cls.kind) {
            case CLS_CHAT: {
                // Spring AI's ChatModelObservationContext.getRequest() returns Prompt.
                String json = (req instanceof org.springframework.ai.chat.prompt.Prompt)
                        ? ClsGenAIFormatter.formatInputMessages(
                                (org.springframework.ai.chat.prompt.Prompt) req)
                        : null;
                if (json == null && req != null) {
                    json = truncate(String.valueOf(req), 4000);  // fallback
                }
                if (json != null) {
                    span.setAttribute(AttributeKey.stringKey("gen_ai.input.messages"),
                            truncate(json, 8000));
                }
                // Request-parameter transparency: CLS GenAI Trace v3 spec adds
                // gen_ai.request.{temperature,top_p,max_tokens,...} to chat
                // spans. Spring AI's Prompt carries these via ChatOptions.
                if (req instanceof org.springframework.ai.chat.prompt.Prompt) {
                    org.springframework.ai.chat.prompt.ChatOptions opts =
                            ((org.springframework.ai.chat.prompt.Prompt) req).getOptions();
                    enrichChatRequestOptions(span, opts);
                }
                break;
            }
            case CLS_EMBEDDING: {
                // EmbeddingModelObservationContext.getRequest() returns
                // EmbeddingRequest with getInstructions() → List<String>.
                // The input is NOT a chat message list, so we must NOT write it
                // under gen_ai.input.messages — CLS would mis-render it. Use
                // gen_ai.embeddings.input instead (a JSON array of strings).
                enrichEmbeddingRequest(span, req);
                break;
            }
            case CLS_TOOL: {
                // ToolCallingObservationContext.getToolCallArguments() returns a JSON
                // string already — no reflection into Prompt/ChatResponse needed.
                String args = invokeString(context, "getToolCallArguments");
                if (args == null && req != null) {
                    args = ClsGenAIFormatter.formatToolArguments(req);
                }
                if (args != null) {
                    span.setAttribute(AttributeKey.stringKey("gen_ai.tool.call.arguments"),
                            truncate(args, 8000));
                }
                // tool.type: prefer the real value from Spring AI 2.0's
                // ToolCallingObservationContext.getToolType() (returns "function"
                // / "mcp" / "retrieval"). Fall back to the generic "function" if
                // the context predates that getter.
                String toolType = invokeString(context, "getToolType");
                span.setAttribute("gen_ai.tool.type",
                        toolType != null && !toolType.isEmpty() ? toolType : "function");
                break;
            }
            case CLS_STEP: {
                // Steps (advisors) occasionally see a Prompt-shaped request too
                // (e.g. QuestionAnswerAdvisor sees the augmented prompt).
                String json = (req instanceof org.springframework.ai.chat.prompt.Prompt)
                        ? ClsGenAIFormatter.formatInputMessages(
                                (org.springframework.ai.chat.prompt.Prompt) req)
                        : null;
                if (json == null && req != null) {
                    json = truncate(String.valueOf(req), 4000);
                }
                if (json != null) {
                    span.setAttribute(AttributeKey.stringKey("gen_ai.input.messages"),
                            truncate(json, 8000));
                }
                break;
            }
            case CLS_RETRIEVAL: {
                // Spring AI 2.0's VectorStoreObservationContext exposes the
                // SearchRequest via getQueryRequest(); its getQuery() returns
                // the raw user query text that the spec names
                // gen_ai.retrieval.query_text.
                enrichRetrievalRequest(span, context);
                break;
            }
            default:
                if (req != null) {
                    span.setAttribute(AttributeKey.stringKey("gen_ai.request.summary"),
                            truncate(String.valueOf(req), 4000));
                }
        }
    }

    /**
     * Enrich a {@code retriever} span with its SearchRequest fields.
     *
     * <p>Reads reflectively off {@code VectorStoreObservationContext.getQueryRequest()}
     * so the SDK keeps compiling even when the vector-store module isn't on the
     * classpath. Writes two spec-required / spec-recommended fields:
     * <ul>
     *   <li>{@code gen_ai.retrieval.query_text} — user query (required)</li>
     *   <li>{@code gen_ai.request.top_k} — requested top-k when the search
     *       request carries one (optional but useful for RAG tuning)</li>
     * </ul>
     */
    private static void enrichRetrievalRequest(Span span, Observation.Context context) {
        try {
            Object sr = invokeFirst(context, "getQueryRequest");
            if (sr == null) return;
            String query = null;
            try {
                Method m = sr.getClass().getMethod("getQuery");
                m.setAccessible(true);
                Object v = m.invoke(sr);
                if (v != null) query = v.toString();
            } catch (NoSuchMethodException ignored) { /* older API */ }
            if (query != null && !query.isEmpty()) {
                span.setAttribute(AttributeKey.stringKey("gen_ai.retrieval.query_text"),
                        truncate(query, 4000));
            }
            try {
                Method m = sr.getClass().getMethod("getTopK");
                m.setAccessible(true);
                Object v = m.invoke(sr);
                if (v instanceof Integer) {
                    span.setAttribute("gen_ai.request.top_k", ((Integer) v).longValue());
                }
            } catch (NoSuchMethodException ignored) { /* older API */ }
        } catch (Throwable ignored) { /* best-effort */ }
    }

    /**
     * Enrich a {@code chat} span with the request parameters carried in Spring
     * AI's {@link org.springframework.ai.chat.prompt.ChatOptions}. Matches the
     * CLS GenAI Trace v3 spec {@code gen_ai.request.*} fields.
     *
     * <p>Only writes fields whose getters return non-null, so the span won't
     * carry misleading defaults (e.g. {@code temperature=0.0} when the model
     * is actually using the provider's default). Covers all seven fields the
     * common ChatOptions interface exposes:
     * <ul>
     *   <li>{@code gen_ai.request.model}       (also already written by Spring AI's own tag)</li>
     *   <li>{@code gen_ai.request.temperature}</li>
     *   <li>{@code gen_ai.request.top_p}</li>
     *   <li>{@code gen_ai.request.top_k}</li>
     *   <li>{@code gen_ai.request.max_tokens}</li>
     *   <li>{@code gen_ai.request.frequency_penalty}</li>
     *   <li>{@code gen_ai.request.presence_penalty}</li>
     *   <li>{@code gen_ai.request.stop_sequences} (as JSON string array)</li>
     * </ul>
     *
     * <p>Plus reflectively tries {@code getSeed()} (only present on provider-
     * specific subclasses like OpenAiChatOptions / AnthropicChatOptions \u2014
     * not on the common ChatOptions interface) so we don't miss it when
     * available.
     */
    private static void enrichChatRequestOptions(Span span,
            org.springframework.ai.chat.prompt.ChatOptions opts) {
        if (opts == null) return;
        try {
            String model = opts.getModel();
            if (model != null && !model.isEmpty()) {
                span.setAttribute("gen_ai.request.model", model);
            }
            Double temperature = opts.getTemperature();
            if (temperature != null) {
                span.setAttribute("gen_ai.request.temperature", temperature);
            }
            Double topP = opts.getTopP();
            if (topP != null) {
                span.setAttribute("gen_ai.request.top_p", topP);
            }
            Integer topK = opts.getTopK();
            if (topK != null) {
                span.setAttribute("gen_ai.request.top_k", topK.longValue());
            }
            Integer maxTokens = opts.getMaxTokens();
            if (maxTokens != null) {
                span.setAttribute("gen_ai.request.max_tokens", maxTokens.longValue());
            }
            Double freqPenalty = opts.getFrequencyPenalty();
            if (freqPenalty != null) {
                span.setAttribute("gen_ai.request.frequency_penalty", freqPenalty);
            }
            Double presPenalty = opts.getPresencePenalty();
            if (presPenalty != null) {
                span.setAttribute("gen_ai.request.presence_penalty", presPenalty);
            }
            java.util.List<String> stops = opts.getStopSequences();
            if (stops != null && !stops.isEmpty()) {
                // Emit as a JSON string array so CLS can decode it uniformly
                // with other array-valued fields (e.g. finish_reasons).
                StringBuilder sb = new StringBuilder(64).append('[');
                boolean first = true;
                for (String s : stops) {
                    if (!first) sb.append(',');
                    first = false;
                    sb.append(ClsGenAIFormatter.quote(s == null ? "" : s));
                }
                sb.append(']');
                span.setAttribute("gen_ai.request.stop_sequences", sb.toString());
            }
        } catch (Throwable ignored) { /* best-effort \u2014 don't fail the span over tagging */ }

        // Provider-specific seed (OpenAiChatOptions.getSeed() etc.) \u2014 reflected
        // so the SDK doesn't drag in provider jars. Spec v3 lists this field.
        try {
            Method m = opts.getClass().getMethod("getSeed");
            m.setAccessible(true);
            Object v = m.invoke(opts);
            if (v instanceof Number) {
                span.setAttribute("gen_ai.request.seed", ((Number) v).longValue());
            }
        } catch (NoSuchMethodException ignored) { /* common ChatOptions has no seed */ }
        catch (Throwable ignored) { /* non-fatal */ }
    }

    /**
     * Enrich an embedding step span with its input texts. Spring AI's
     * EmbeddingRequest.getInstructions() returns a List<String> of texts to be
     * embedded; we emit it as a JSON array under {@code gen_ai.embeddings.input}
     * so CLS can render the raw text without the misleading chat-message shape.
     *
     * <p>We also set {@code gen_ai.embeddings.input_count} to the number of
     * texts — useful when the caller batches multiple embeddings in one call.
     */
    private static void enrichEmbeddingRequest(Span span, Object req) {
        if (req == null) return;
        try {
            if (req instanceof org.springframework.ai.embedding.EmbeddingRequest) {
                org.springframework.ai.embedding.EmbeddingRequest er =
                        (org.springframework.ai.embedding.EmbeddingRequest) req;
                java.util.List<String> inputs = er.getInstructions();
                if (inputs != null && !inputs.isEmpty()) {
                    StringBuilder sb = new StringBuilder(256).append('[');
                    boolean first = true;
                    for (String s : inputs) {
                        if (!first) sb.append(',');
                        first = false;
                        sb.append(ClsGenAIFormatter.quote(s == null ? "" : s));
                    }
                    sb.append(']');
                    span.setAttribute(AttributeKey.stringKey("gen_ai.embeddings.input"),
                            truncate(sb.toString(), 8000));
                    span.setAttribute("gen_ai.embeddings.input_count", inputs.size());
                }
                return;
            }
        } catch (Throwable ignored) { /* fall through to summary */ }
        // Unknown shape — at least record something readable instead of toString.
        span.setAttribute(AttributeKey.stringKey("gen_ai.request.summary"),
                truncate(String.valueOf(req), 4000));
    }

    private void enrichResponse(Span span, Observation.Context context, Classification cls) {
        Object resp = invokeResponse(context,
                "getResponse", "getResult");
        switch (cls.kind) {
            case CLS_CHAT: {
                String json = (resp instanceof org.springframework.ai.chat.model.ChatResponse)
                        ? ClsGenAIFormatter.formatOutputMessages(
                                (org.springframework.ai.chat.model.ChatResponse) resp)
                        : null;
                if (json == null && resp != null) {
                    json = truncate(String.valueOf(resp), 8000);
                }
                if (json != null) {
                    span.setAttribute(AttributeKey.stringKey("gen_ai.output.messages"),
                            truncate(json, 8000));
                }
                // Cache the ChatResponse on the enclosing turn so the agent/entry
                // spans can bubble up the final chat output on turn stop.
                if (resp instanceof org.springframework.ai.chat.model.ChatResponse) {
                    TurnState ts = findTurnState(context);
                    if (ts != null) {
                        ts.lastChatResponse = (org.springframework.ai.chat.model.ChatResponse) resp;
                    }
                }
                break;
            }
            case CLS_EMBEDDING: {
                // Embedding output is a list of vectors — we don't persist the
                // vectors themselves (hundreds of floats, mostly noise in a trace
                // UI), only the shape: count + dimensions.
                enrichEmbeddingResponse(span, resp);
                break;
            }
            case CLS_TOOL: {
                // ToolCallingObservationContext.getToolCallResult() returns the raw
                // tool return value as a string — already the shape CLS wants.
                String result = invokeString(context, "getToolCallResult");
                if (result == null && resp != null) {
                    result = truncate(String.valueOf(resp), 8000);
                }
                if (result != null) {
                    span.setAttribute(AttributeKey.stringKey("gen_ai.tool.call.result"),
                            truncate(result, 8000));
                }
                break;
            }
            case CLS_STEP: {
                String json = (resp instanceof org.springframework.ai.chat.model.ChatResponse)
                        ? ClsGenAIFormatter.formatOutputMessages(
                                (org.springframework.ai.chat.model.ChatResponse) resp)
                        : null;
                if (json == null && resp != null) {
                    json = truncate(String.valueOf(resp), 8000);
                }
                if (json != null) {
                    span.setAttribute(AttributeKey.stringKey("gen_ai.output.messages"),
                            truncate(json, 8000));
                }
                break;
            }
            case CLS_RETRIEVAL: {
                // Spring AI's VectorStoreObservationContext exposes the result
                // list via getQueryResponse() — we only record its size here
                // (document contents are large and default-off per spec).
                try {
                    Object list = invokeFirst(context, "getQueryResponse");
                    if (list instanceof java.util.Collection) {
                        span.setAttribute("gen_ai.retrieval.documents.count",
                                (long) ((java.util.Collection<?>) list).size());
                    }
                } catch (Throwable ignored) { /* best-effort */ }
                break;
            }
            default:
                if (resp != null) {
                    span.setAttribute(AttributeKey.stringKey("gen_ai.response.summary"),
                            truncate(String.valueOf(resp), 8000));
                }
        }
    }

    /**
     * Enrich an embedding step span with its output shape. We never log the raw
     * vectors — they are high-dimensional noise in a trace UI and bloat span
     * payloads. Instead we record {@code gen_ai.embeddings.vector_count} and
     * {@code gen_ai.embeddings.vector_dimensions}, which are enough to spot
     * dimension mismatches and empty responses at a glance.
     *
     * <p>Spring AI's {@code Embedding.getOutput()} returns {@code float[]} as of
     * 1.0 GA; we probe it reflectively so older/newer embeddings don't trip us.
     */
    private static void enrichEmbeddingResponse(Span span, Object resp) {
        if (resp == null) return;
        try {
            if (resp instanceof org.springframework.ai.embedding.EmbeddingResponse) {
                org.springframework.ai.embedding.EmbeddingResponse er =
                        (org.springframework.ai.embedding.EmbeddingResponse) resp;
                java.util.List<org.springframework.ai.embedding.Embedding> results = er.getResults();
                int count = results == null ? 0 : results.size();
                span.setAttribute("gen_ai.embeddings.vector_count", count);
                if (count > 0) {
                    int dim = probeVectorDimensions(results.get(0));
                    if (dim > 0) {
                        span.setAttribute("gen_ai.embeddings.vector_dimensions", dim);
                    }
                }
                return;
            }
        } catch (Throwable ignored) { /* fall through */ }
        span.setAttribute(AttributeKey.stringKey("gen_ai.response.summary"),
                truncate(String.valueOf(resp), 4000));
    }

    /** Reflectively read an Embedding's vector length, trying both the
     *  Spring AI 1.0 ({@code float[] getOutput()}) and any future list-based
     *  shapes. Returns 0 if the shape is unknown. */
    private static int probeVectorDimensions(Object embedding) {
        if (embedding == null) return 0;
        for (String getter : new String[] { "getOutput", "getEmbedding" }) {
            try {
                Method m = embedding.getClass().getMethod(getter);
                m.setAccessible(true);
                Object v = m.invoke(embedding);
                if (v == null) continue;
                if (v instanceof float[])  return ((float[])  v).length;
                if (v instanceof double[]) return ((double[]) v).length;
                if (v instanceof int[])    return ((int[])    v).length;
                if (v instanceof java.util.Collection) return ((java.util.Collection<?>) v).size();
            } catch (NoSuchMethodException ignored) { /* try next */ }
            catch (Throwable ignored) { return 0; }
        }
        return 0;
    }

    /** For the ChatClient (turn) start — capture the user prompt / message list.
     *  Priority: reflective access on the Spring AI context subclass → Micrometer
     *  high/low cardinality tag {@code gen_ai.input.messages} (fallback for
     *  frameworks or tests that drive observations with the generic
     *  {@link Observation.Context}). */
    private static String extractInputMessages(Observation.Context context) {
        Object req = invokeRequest(context,
                "getRequest", "getPrompt", "getRequestOptions");
        if (req != null) {
            // ChatClientObservationContext.getRequest() → ChatClientRequest
            // (a Java record exposing prompt() → Prompt). Unwrap one level.
            if (req instanceof org.springframework.ai.chat.client.ChatClientRequest) {
                org.springframework.ai.chat.prompt.Prompt p =
                        ((org.springframework.ai.chat.client.ChatClientRequest) req).prompt();
                String json = ClsGenAIFormatter.formatInputMessages(p);
                if (json != null) return json;
            }
            // ChatModelObservationContext.getRequest() → Prompt directly.
            if (req instanceof org.springframework.ai.chat.prompt.Prompt) {
                String json = ClsGenAIFormatter.formatInputMessages(
                        (org.springframework.ai.chat.prompt.Prompt) req);
                if (json != null) return json;
            }
            // Last resort — toString() so we don't silently drop content.
            return String.valueOf(req);
        }
        return findTagValue(context, "gen_ai.input.messages", "gen_ai.prompt");
    }

    /** For the ChatClient (turn) stop — capture the assistant response.
     *  ChatClientObservationContext does <b>not</b> expose a getResponse()
     *  method (verified via {@code javap}), so we instead bubble up the last
     *  nested ChatModel response that was captured in {@link TurnState}. */
    private static String extractOutputMessages(Observation.Context context) {
        TurnState ts = context.get(TurnState.class);
        if (ts != null && ts.lastChatResponse != null) {
            String json = ClsGenAIFormatter.formatOutputMessages(ts.lastChatResponse);
            if (json != null) return json;
        }
        // Fallback for non-ChatClient contexts or frameworks that do populate
        // the response accessor directly (ChatModelObservationContext etc.).
        Object resp = invokeResponse(context,
                "getResponse", "getResult");
        if (resp instanceof org.springframework.ai.chat.model.ChatResponse) {
            String json = ClsGenAIFormatter.formatOutputMessages(
                    (org.springframework.ai.chat.model.ChatResponse) resp);
            if (json != null) return json;
        }
        if (resp != null) return String.valueOf(resp);
        return findTagValue(context, "gen_ai.output.messages", "gen_ai.completion");
    }

    // ══════════════════════════════════════════════════════════════════════
    //  Per-observation state carriers
    // ══════════════════════════════════════════════════════════════════════

    /**
     * Attached to each {@link Observation.Context} via its own put/get API —
     * holds the span we created in onStart so onStop can finish it.
     */
    private static final class SpanState {
        final Span span;
        final Scope scope;
        /** Only set when this observation IS the turn boundary (ChatClient). */
        final Span entrySpan;
        final boolean isTurn;
        /** When true, this state is a no-op placeholder for a nested advisor
         *  that was folded into the enclosing react span (Spring AI 2.0). */
        final boolean collapsed;
        final Classification classification;
        /** {@link System#nanoTime()} snapshot at onStart; used to compute the
         *  spec-defined duration_ms field for leaf spans (chat / tool /
         *  retrieval) at onStop. 0 for collapsed placeholders. */
        final long startNanos;
        /** When {@link #isTurn} is true, distinguishes root turn ({@code false})
         *  from subagent turn ({@code true}). Drives {@code onStop} to pick the
         *  right teardown path — subagent closes only its own agent span and
         *  pops the TurnState stack; root closes agent + entry together. */
        final boolean isSubAgent;
        /** Optional OTel Context scope that publishes {@link #CLS_TOOL_INFO_KEY}
         *  for a tool observation. Set from onStart (tool branch) after the
         *  main span scope is pushed; closed in onStop BEFORE the main span
         *  scope so the Context stack unwinds in LIFO order. Null for
         *  non-tool spans. */
        volatile Scope toolInfoScope;
        SpanState(Span span, Scope scope, Span entrySpan, boolean isTurn, Classification cls) {
            this(span, scope, entrySpan, isTurn, /*isSubAgent*/ false, cls);
        }
        /** Full ctor used by subagent turn starts, which pass {@code isTurn=true,
         *  isSubAgent=true, entrySpan=null}. */
        SpanState(Span span, Scope scope, Span entrySpan, boolean isTurn,
                  boolean isSubAgent, Classification cls) {
            this.span = span; this.scope = scope;
            this.entrySpan = entrySpan; this.isTurn = isTurn;
            this.isSubAgent = isSubAgent;
            this.collapsed = false;
            this.classification = cls;
            this.startNanos = System.nanoTime();
        }
        /** Private ctor for collapsed placeholders. */
        private SpanState(Classification cls) {
            this.span = null; this.scope = null;
            this.entrySpan = null; this.isTurn = false;
            this.isSubAgent = false;
            this.collapsed = true;
            this.classification = cls;
            this.startNanos = 0L;
        }
        static SpanState collapsed(Classification cls) { return new SpanState(cls); }
    }

    private static final Class<SpanState> SPAN_STATE_KEY = SpanState.class;

    private static void setSpanState(Observation.Context ctx, SpanState state) {
        ctx.put(SPAN_STATE_KEY, state);
    }

    private static SpanState getSpanState(Observation.Context ctx) {
        return ctx.get(SPAN_STATE_KEY);
    }

    /**
     * Shared state for one turn (one ChatClient call). Attached to the
     * ChatClient Observation.Context; nested observations look it up by
     * walking the current OTel context lineage via {@link #findTurnState}.
     */
    private static final class TurnState {
        final String sessionId;
        final String userId;
        final String userName;
        final String turnId;
        /** SDK extension: whether this turn is a streaming ChatClient call
         *  ({@code .stream()} vs {@code .call()}). Read once from the
         *  ChatClientObservationContext at turn start; chat spans inherit it. */
        volatile boolean streaming;
        final AtomicInteger stepCounter = new AtomicInteger(0);
        volatile String currentStepId;
        /** Stack of enclosing react step.ids — pushed on react onStart,
         *  popped on react onStop. Enables correct step.id restoration for
         *  sibling reacts after a nested advisor collapses (Spring AI 2.0). */
        final Deque<String> stepIdStack = new ArrayDeque<>();
        volatile Span entrySpan;
        volatile Span agentSpan;
        volatile Scope entryScope;
        volatile Scope agentScope;
        /** Last ChatResponse seen inside the turn, used to populate
         *  {@code gen_ai.output.messages} on the agent/entry spans at turn stop
         *  (ChatClientObservationContext itself has no getResponse accessor). */
        volatile org.springframework.ai.chat.model.ChatResponse lastChatResponse;

        // ── ReAct round tracking (B + C features, Z-2 strategy) ──────────────
        // Spring AI 2.0 wraps a whole multi-round ReAct cycle in a single
        // `spring.ai.advisor` observation, so the SDK itself must split rounds
        // if we want each LLM turn to render as its own react span on CLS.
        //
        // Strategy Z-2 (lazy switch): the first round reuses Spring AI's
        // native advisor span; later rounds get fresh SDK-synthesised react
        // spans. The switch happens on the next chat.onStart after a chat
        // whose finish_reason == "tool_calls" — i.e. we lazily create a new
        // react when we see the first child of the next round, not eagerly
        // when the previous tool_calls completes.

        /** Last chat.onStop's finish_reason (lowercased: "stop" | "tool_calls"
         *  | "length" | "content_filter" | ...). null before any chat completes.
         *  Drives the lazy round switch on the next chat.onStart. */
        volatile String lastFinishReason;
        /** Currently-active react span. Points at Spring AI's native advisor
         *  span for round 1, then flips to each SDK-synthesised react span
         *  for rounds 2+. Children (chat/embedding/tool) attach to this. */
        volatile Span activeReactSpan;
        /** Scope held open for the currently-active SDK-synthesised react
         *  (round 2+). Null when the active react is Spring AI's native one —
         *  that scope is managed by Spring AI's own ObservationHandler flow. */
        volatile Scope activeReactScope;
        /** true = {@link #activeReactSpan} is Spring AI's native advisor span
         *  (round 1); false = SDK-synthesised (round 2+). */
        volatile boolean activeReactIsNative;
        /** Set true when we lazily end()'ed the native advisor span early
         *  (because round 2 started). The subsequent advisor.onStop must then
         *  skip the span.end() call to avoid double-ending. */
        volatile boolean nativeReactPrematurelyEnded;
        /** 1-based round counter; written to each child span as `gen_ai.round`. */
        final AtomicInteger roundCounter = new AtomicInteger(1);

        // ── Subagent support (CLS v5 invoke_subagent) ────────────────────
        /** true when this TurnState represents a nested ChatClient call — i.e.
         *  a subagent invoked from inside a tool handler. Subagents reuse the
         *  outer turn's {@code session.id} and {@code turn.id} (one trace, one
         *  entry) and only open an agent-kind span, never an entry. */
        final boolean isSubAgent;
        /** For subagents, points at the enclosing TurnState so onStop can
         *  restore it to {@link #CURRENT_TURN}. Null for root turns.
         *  Non-final because {@link #release()} clears it at turn teardown. */
        volatile TurnState outer;
        /** For subagents, the name copied from the tool that triggered this
         *  nested call (strategy 1). Root turns use "main". */
        final String agentName;
        /** For subagents, the {@code gen_ai.tool.call.id} of the triggering
         *  tool, written as {@code gen_ai.subagent.parent_tool_call.id}. Null
         *  when the trigger cannot be identified. */
        final String parentToolCallId;
        /** OTel Context Scope that attaches this TurnState to {@link #CLS_TURN_KEY}.
         *  Closed in onStop so nested detection sees the previous TurnState
         *  (or null) when this subagent finishes. */
        volatile Scope ctxScope;
        /** Fallback counter used to synthesise an agent name when the parent
         *  tool name cannot be reflected. Only incremented on cache miss. */
        final AtomicInteger subAgentCounter = new AtomicInteger(0);

        TurnState(String sessionId, String userId, String turnId) {
            this(sessionId, userId, null, turnId);
        }

        TurnState(String sessionId, String userId, String userName, String turnId) {
            this(sessionId, userId, userName, turnId,
                 /*isSubAgent*/ false, /*outer*/ null,
                 /*agentName*/ "main", /*parentToolCallId*/ null);
        }

        private TurnState(String sessionId, String userId, String userName, String turnId,
                          boolean isSubAgent, TurnState outer,
                          String agentName, String parentToolCallId) {
            this.sessionId = sessionId;
            this.userId = userId;
            this.userName = userName;
            this.turnId = turnId;
            this.isSubAgent = isSubAgent;
            this.outer = outer;
            this.agentName = agentName;
            this.parentToolCallId = parentToolCallId;
        }

        /**
         * Drop every reference that could keep an OTel Scope / Span / response
         * reachable after the turn has been torn down.
         *
         * <p>Why: for {@code ChatClient.stream()} the turn's onStop may run on a
         * Reactor worker, where {@code Scope.close()} is a no-op because that
         * thread's current Context is not the Scope's {@code toAttach}. The
         * originating (e.g. Tomcat) thread keeps a residual Context whose
         * {@link #CLS_TURN_KEY} entry points back to this TurnState. A later
         * Scope opened on that thread records the residual Context as its
         * {@code beforeAttach}, and this TurnState references its own Scopes —
         * so without this method every turn stays reachable from the next one
         * and memory grows linearly with the number of turns.
         *
         * <p>Must be called only after all scopes/spans of the turn have been
         * closed/ended and after {@link #outer} has been consumed. Idempotent.
         */
        void release() {
            entryScope = null;
            agentScope = null;
            ctxScope = null;
            activeReactScope = null;
            entrySpan = null;
            agentSpan = null;
            activeReactSpan = null;
            lastChatResponse = null;
            outer = null;
            stepIdStack.clear();
        }

        /** Factory for a root TurnState (outer-most ChatClient call). */
        static TurnState root(String sessionId, String userId, String userName, String turnId) {
            return new TurnState(sessionId, userId, userName, turnId,
                                 /*isSubAgent*/ false, /*outer*/ null,
                                 /*agentName*/ "main", /*parentToolCallId*/ null);
        }

        /** Factory for a subagent TurnState. Reuses the outer turn's session.id
         *  and turn.id so the whole call chain stays in one trace / one turn,
         *  matching the CLS v5 spec ("1 trace = 1 entry, 1 turn = N subagents"). */
        static TurnState subAgent(TurnState outer, String agentName, String parentToolCallId) {
            return new TurnState(outer.sessionId, outer.userId, outer.userName,
                                 outer.turnId,  // reuse outer turn.id
                                 /*isSubAgent*/ true, outer,
                                 agentName, parentToolCallId);
        }
    }

    /**
     * Walk up the Observation hierarchy to find the enclosing ChatClient turn.
     * Micrometer threads the parent context through {@link Observation.Context
     * #getParentObservation()}, so nested advisor/tool/chat obs can always
     * locate their turn by walking parents.
     */
    private static TurnState findTurnState(Observation.Context ctx) {
        Observation.Context cursor = ctx;
        while (cursor != null) {
            TurnState ts = cursor.get(TurnState.class);
            if (ts != null) return ts;
            io.micrometer.observation.ObservationView parent = cursor.getParentObservation();
            if (parent == null) {
                cursor = null;
            } else {
                io.micrometer.observation.Observation.ContextView pctx = parent.getContextView();
                cursor = (pctx instanceof Observation.Context) ? (Observation.Context) pctx : null;
            }
        }
        // ── Fallback: ThreadLocal lookup ──────────────────────────────────
        // Spring AI 2.0 occasionally emits observations (notably EmbeddingModel
        // calls from inside QuestionAnswerAdvisor's second RAG round) whose
        // parent chain does NOT lead back to the ChatClient's observation —
        // getParentObservation() returns null before reaching our TurnState.
        // The OTel Span context is still correct (because our agentScope is
        // makeCurrent()-ed on the thread), so we fall back to the thread-local
        // we populated at startChatClientTurn time.
        return CURRENT_TURN.get();
    }

    /** Thread-local pointer to the currently-active TurnState, kept in sync
     *  with the ChatClient turn's lifecycle. Primary lookup still goes through
     *  the observation context chain; this is only consulted when that chain
     *  is broken (see {@link #findTurnState}). */
    private static final ThreadLocal<TurnState> CURRENT_TURN = new ThreadLocal<>();

    /** OpenTelemetry {@link Context} key under which the active {@link TurnState}
     *  is attached. This is the <b>primary</b> channel used by
     *  {@link #startChatClientTurn} to detect nested ChatClient calls (which
     *  become CLS subagents). Context propagation is honoured by OTel across
     *  threads — so reactive flows ({@code ChatClient.stream()},
     *  CompletableFuture, WebFlux) stay correct as long as the host app
     *  propagates OTel Context (which Spring AI already does for its own
     *  instrumentation). ThreadLocal {@link #CURRENT_TURN} remains as a
     *  fallback for the rare case where Context propagation is broken but
     *  the call stays on the same thread. */
    private static final ContextKey<TurnState> CLS_TURN_KEY =
            ContextKey.named("cls.turn-state");

    /** Payload for {@link #CLS_TOOL_INFO_KEY}: the identity (name + call id)
     *  of the tool currently being executed on the thread. */
    private static final class ToolInfo {
        final String name;
        final String callId;
        ToolInfo(String name, String callId) { this.name = name; this.callId = callId; }
    }

    /** OpenTelemetry {@link Context} key under which the currently-executing
     *  tool's identity is published while a {@code spring.ai.tool} observation
     *  is active on this thread.
     *
     *  <p>Rationale: in the outer-stream + inner-stream subagent scenario the
     *  tool observation's parent chain is invisible to inner's
     *  {@code Observation.Context} (built at Flux assembly time, before
     *  Reactor Context restoration), AND the Reactor Context stashed by
     *  {@code ToolCallReactiveContextHolder} contains only the outer
     *  ChatClient / advisor observations (tool obs is synchronous-lifetimed
     *  and never enters the Reactor Context). But empirically the tool
     *  observation's onStart and the inner ChatClient's onStart run on the
     *  SAME Reactor worker thread, so the OTel Context attached by tool's
     *  onStart is still current when inner's onStart executes. We leverage
     *  this to carry tool.name and tool.call.id across the (synchronous,
     *  same-thread) gap. */
    private static final ContextKey<ToolInfo> CLS_TOOL_INFO_KEY =
            ContextKey.named("cls.tool-info");
}
