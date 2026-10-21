package com.tencentcloudapi.observability.springai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.lang.reflect.Method;
import java.util.Collections;

import org.junit.jupiter.api.Test;

import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanContext;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.TraceFlags;
import io.opentelemetry.api.trace.TraceState;
import io.opentelemetry.context.Context;
import io.opentelemetry.sdk.trace.samplers.SamplingDecision;
import io.opentelemetry.sdk.trace.samplers.Sampler;

/**
 * Guards the sampling invariant the SDK's trace tree depends on:
 * <b>a turn is sampled whole or not at all</b>.
 *
 * <p>Only {@code enter_application} is a trace root; every other span the
 * handler emits (agent / react / chat / tool / embedding / retrieval, plus any
 * nested {@code invoke_subagent} subtree) is created with an explicit parent.
 * Wrapping the ratio sampler in {@code ParentBased} is therefore what stops a
 * child span from re-rolling the dice and producing a trace with holes in it
 * on the CLS console.
 *
 * <p>These assertions pin that contract against the real OTel sampler rather
 * than restating it in a comment.
 */
class ClsSamplerTest {

    /**
     * Trace ids chosen against OTel's {@code TraceIdRatioBasedSampler}, which
     * reads the <b>last 8 bytes</b> of the trace id as a signed long, takes
     * {@code Math.abs}, and samples while that value is {@code <} the
     * threshold {@code (long) (ratio * Long.MAX_VALUE)}. At ratio {@code 0.5}
     * the threshold is ~{@code 4.61e18}, so:
     */
    /** Last 8 bytes = {@code 0x6000...} ≈ 6.92e18 → above the threshold → DROP. */
    private static final String TRACE_ID_DROPPED_AT_HALF = "00000000000000006000000000000000";
    /** Last 8 bytes = {@code 1} → below the threshold → sampled. */
    private static final String TRACE_ID_SAMPLED_AT_HALF = "00000000000000000000000000000001";

    /** Invoke the private static {@code buildSampler(ClsConfig)}. */
    private static Sampler samplerFor(double ratio) throws Exception {
        ClsConfig config = ClsConfig.builder()
                .endpoint("https://ap-guangzhou.cls.tencentcs.com")
                .topicId("topic-test")
                .secretId("id-test")
                .secretKey("key-test")
                .sampleRatio(ratio)
                .build();
        Method m = ClsObservability.class.getDeclaredMethod("buildSampler", ClsConfig.class);
        m.setAccessible(true);
        return (Sampler) m.invoke(null, config);
    }

    /** A context whose current span is a parent carrying the given flags. */
    private static Context parentWith(TraceFlags flags) {
        SpanContext parent = SpanContext.create(
                "0af7651916cd43dd8448eb211c80319c", "b7ad6b7169203331",
                flags, TraceState.getDefault());
        return Context.root().with(Span.wrap(parent));
    }

    private static SamplingDecision decide(Sampler sampler, Context parent, String traceId) {
        return sampler.shouldSample(parent, traceId, "chat", SpanKind.CLIENT,
                Attributes.empty(), Collections.emptyList()).getDecision();
    }

    @Test
    void ratioOneIsAlwaysOn() throws Exception {
        assertSame(Sampler.alwaysOn(), samplerFor(1.0d));
    }

    @Test
    void ratioZeroIsAlwaysOff() throws Exception {
        assertSame(Sampler.alwaysOff(), samplerFor(0.0d));
    }

    /** With no parent, the ratio actually applies — that's the volume knob. */
    @Test
    void rootSpanObeysRatio() throws Exception {
        Sampler sampler = samplerFor(0.5d);
        assertEquals(SamplingDecision.DROP,
                decide(sampler, Context.root(), TRACE_ID_DROPPED_AT_HALF));
        assertEquals(SamplingDecision.RECORD_AND_SAMPLE,
                decide(sampler, Context.root(), TRACE_ID_SAMPLED_AT_HALF));
    }

    /**
     * The crux: with a partial ratio, a child span must follow its parent's
     * verdict in both directions, so a turn never ends up half-exported.
     *
     * <p>Both assertions deliberately pair a parent flag with the trace id
     * that would have produced the <i>opposite</i> verdict on its own (see
     * {@link #rootSpanObeysRatio}), so the parent's decision is provably the
     * deciding factor rather than a coincidence.
     */
    @Test
    void childInheritsParentDecision() throws Exception {
        Sampler sampler = samplerFor(0.5d);

        // Would be DROPped as a root, but its parent is sampled → keep it,
        // otherwise the parent's trace would lose this subtree.
        assertEquals(SamplingDecision.RECORD_AND_SAMPLE,
                decide(sampler, parentWith(TraceFlags.getSampled()),
                        TRACE_ID_DROPPED_AT_HALF));

        // Would be sampled as a root, but its parent was dropped → drop it,
        // otherwise a sampled-out turn would leak orphan spans into CLS.
        assertEquals(SamplingDecision.DROP,
                decide(sampler, parentWith(TraceFlags.getDefault()),
                        TRACE_ID_SAMPLED_AT_HALF));
    }
}
