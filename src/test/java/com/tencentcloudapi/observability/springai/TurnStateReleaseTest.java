package com.tencentcloudapi.observability.springai;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayDeque;
import java.util.Collections;
import java.util.Deque;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatResponse;

import io.opentelemetry.api.trace.Span;
import io.opentelemetry.context.Scope;

/**
 * Guards the memory-leak fix for sequential {@code ChatClient.stream()} calls.
 *
 * <p>When a turn's {@code onStop} runs on a Reactor worker, {@code Scope.close()}
 * is a no-op on the originating thread, leaving a residual OTel Context whose
 * {@code CLS_TURN_KEY} points back at the finished {@code TurnState}. If that
 * TurnState keeps referencing its own Scopes / Spans / ChatResponse / outer
 * turn, every turn stays reachable from the next and heap grows linearly.
 *
 * <p>{@code TurnState#release()} must sever all of those references. This test
 * reflects into the (private) {@code TurnState} to pin that contract
 * deterministically — without relying on GC timing.
 */
class TurnStateReleaseTest {

    @Test
    void release_clearsAllRetainingReferences() throws Exception {
        Class<?> turnStateCls = Class.forName(
                "com.tencentcloudapi.observability.springai.ClsObservationHandler$TurnState");

        // Build a root TurnState via its package-private static factory.
        Method rootFactory = turnStateCls.getDeclaredMethod(
                "root", String.class, String.class, String.class, String.class);
        rootFactory.setAccessible(true);
        Object ts = rootFactory.invoke(null, "sess-1", "user-1", "name-1", "sess-1:t1");

        // A nested subagent whose `outer` points back at the root turn.
        Method subFactory = turnStateCls.getDeclaredMethod(
                "subAgent", turnStateCls, String.class, String.class);
        subFactory.setAccessible(true);
        Object subTs = subFactory.invoke(null, ts, "my-tool", "call-1");

        Span span = Span.getInvalid();
        Scope scope = Scope.noop();
        ChatResponse resp = new ChatResponse(Collections.emptyList());

        // Populate every reference-holding field the way onStart/onStop would.
        setField(turnStateCls, ts, "entrySpan", span);
        setField(turnStateCls, ts, "agentSpan", span);
        setField(turnStateCls, ts, "entryScope", scope);
        setField(turnStateCls, ts, "agentScope", scope);
        setField(turnStateCls, ts, "ctxScope", scope);
        setField(turnStateCls, ts, "activeReactScope", scope);
        setField(turnStateCls, ts, "activeReactSpan", span);
        setField(turnStateCls, ts, "lastChatResponse", resp);
        pushStep(turnStateCls, ts, "sess-1:t1:s1");

        setField(turnStateCls, subTs, "agentSpan", span);
        setField(turnStateCls, subTs, "agentScope", scope);
        setField(turnStateCls, subTs, "ctxScope", scope);

        // outer wiring must be in place before release.
        assertTrue(getField(turnStateCls, subTs, "outer") == ts,
                "precondition: subagent.outer should reference the root turn");

        // Act.
        Method release = turnStateCls.getDeclaredMethod("release");
        release.setAccessible(true);
        release.invoke(ts);
        release.invoke(subTs);

        // Assert: no residual references remain on either TurnState.
        for (String f : new String[] {
                "entrySpan", "agentSpan", "entryScope", "agentScope", "ctxScope",
                "activeReactScope", "activeReactSpan", "lastChatResponse", "outer" }) {
            assertNull(getField(turnStateCls, ts, f), "root." + f + " should be null after release");
        }
        for (String f : new String[] { "agentSpan", "agentScope", "ctxScope", "outer" }) {
            assertNull(getField(turnStateCls, subTs, f), "sub." + f + " should be null after release");
        }

        Deque<?> stack = (Deque<?>) getField(turnStateCls, ts, "stepIdStack");
        assertTrue(stack.isEmpty(), "stepIdStack should be cleared after release");
    }

    private static void setField(Class<?> cls, Object target, String name, Object value) throws Exception {
        Field f = cls.getDeclaredField(name);
        f.setAccessible(true);
        f.set(target, value);
    }

    private static Object getField(Class<?> cls, Object target, String name) throws Exception {
        Field f = cls.getDeclaredField(name);
        f.setAccessible(true);
        return f.get(target);
    }

    @SuppressWarnings("unchecked")
    private static void pushStep(Class<?> cls, Object target, String stepId) throws Exception {
        Field f = cls.getDeclaredField("stepIdStack");
        f.setAccessible(true);
        Deque<String> stack = (Deque<String>) f.get(target);
        if (stack == null) {
            stack = new ArrayDeque<>();
            f.set(target, stack);
        }
        stack.push(stepId);
    }
}
