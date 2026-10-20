package com.tencentcloudapi.observability.springai;

import io.micrometer.common.KeyValue;
import io.micrometer.observation.Observation;

import java.lang.reflect.Method;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Stateless adapter that reads values off Spring AI's various
 * {@link Observation.Context} subclasses via reflection, plus small text
 * utilities shared by the handler.
 *
 * <p>Extracted from {@code ClsObservationHandler} so the handler can focus on
 * the span/turn lifecycle state machine while all the version-tolerant
 * reflection probing lives here. Everything is {@code static} and holds no
 * handler state (no tracer / config / turn state), so it is safe to call from
 * any thread. Spring AI 1.x and 2.x expose different accessor names for the
 * same data, hence the "try these getters in order" style.
 */
final class ClsReflect {

    private ClsReflect() {}

    // Reflection caches so we probe each Context subclass's accessors only once.
    private static final ConcurrentMap<Class<?>, Method> REQUEST_ACCESSOR_CACHE = new ConcurrentHashMap<>();
    private static final ConcurrentMap<Class<?>, Method> RESPONSE_ACCESSOR_CACHE = new ConcurrentHashMap<>();
    private static final Method ABSENT_SENTINEL;
    static {
        try { ABSENT_SENTINEL = Object.class.getMethod("toString"); }
        catch (NoSuchMethodException e) { throw new AssertionError(e); }
    }

    /** Cached reflective read of the request object off the context. */
    static Object invokeRequest(Observation.Context context, String... names) {
        return invoke(context, REQUEST_ACCESSOR_CACHE, names);
    }

    /** Cached reflective read of the response object off the context. */
    static Object invokeResponse(Observation.Context context, String... names) {
        return invoke(context, RESPONSE_ACCESSOR_CACHE, names);
    }

    private static Object invoke(Observation.Context context,
                                 ConcurrentMap<Class<?>, Method> cache,
                                 String... names) {
        Class<?> type = context.getClass();
        Method m = cache.get(type);
        if (m == null) {
            m = findAccessor(type, names);
            cache.put(type, m == null ? ABSENT_SENTINEL : m);
        }
        if (m == null || m == ABSENT_SENTINEL) return null;
        try {
            return m.invoke(context);
        } catch (Throwable t) {
            return null;
        }
    }

    /** Returns the first non-null result among the named zero-arg getters. */
    static Object invokeFirst(Observation.Context context, String... names) {
        for (String n : names) {
            try {
                Method m = context.getClass().getMethod(n);
                m.setAccessible(true);
                Object v = m.invoke(context);
                if (v != null) return v;
            } catch (NoSuchMethodException ignored) { /* try next */ }
            catch (Throwable ignored) { /* try next */ }
        }
        return null;
    }

    /** Reflective single-shot invoke — used for stateless accessors like
     *  {@code ToolCallingObservationContext.getToolCallArguments()} that aren't
     *  worth caching separately. */
    static String invokeString(Observation.Context context, String methodName) {
        for (Class<?> c = context.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
            try {
                Method m = c.getDeclaredMethod(methodName);
                m.setAccessible(true);
                Object v = m.invoke(context);
                return v == null ? null : v.toString();
            } catch (NoSuchMethodException ignored) { /* try parent */ }
            catch (Throwable t) { return null; }
        }
        return null;
    }

    private static Method findAccessor(Class<?> type, String... names) {
        for (Class<?> c = type; c != null && c != Object.class; c = c.getSuperclass()) {
            for (String n : names) {
                try {
                    Method m = c.getDeclaredMethod(n);
                    m.setAccessible(true);
                    return m;
                } catch (NoSuchMethodException ignored) { /* try next */ }
            }
        }
        return null;
    }

    /** Reads a tag value by key from the context's high- then low-cardinality
     *  key-values. */
    static String findTagValue(Observation.Context context, String... keys) {
        for (KeyValue kv : context.getHighCardinalityKeyValues()) {
            for (String k : keys) {
                if (k.equals(kv.getKey())) return kv.getValue();
            }
        }
        for (KeyValue kv : context.getLowCardinalityKeyValues()) {
            for (String k : keys) {
                if (k.equals(kv.getKey())) return kv.getValue();
            }
        }
        return null;
    }

    /** The parent observation's context, if it is a Spring AI
     *  {@link Observation.Context}; otherwise null. */
    static Observation.Context parentContext(Observation.Context ctx) {
        io.micrometer.observation.ObservationView parent = ctx.getParentObservation();
        if (parent == null) return null;
        io.micrometer.observation.Observation.ContextView pctx = parent.getContextView();
        return (pctx instanceof Observation.Context) ? (Observation.Context) pctx : null;
    }

    /** Truncate to {@code max} chars with an ellipsis; null-safe. */
    static String truncate(String s, int max) {
        if (s == null) return null;
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }
}
