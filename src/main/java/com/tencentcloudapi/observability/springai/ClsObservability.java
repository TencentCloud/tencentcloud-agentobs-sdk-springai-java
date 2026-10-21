package com.tencentcloudapi.observability.springai;

import io.micrometer.observation.ObservationRegistry;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.common.AttributesBuilder;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.exporter.otlp.http.trace.OtlpHttpSpanExporter;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.resources.Resource;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.export.BatchSpanProcessor;
import io.opentelemetry.sdk.trace.export.SpanExporter;
import io.opentelemetry.sdk.trace.samplers.Sampler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;

/**
 * One-shot setup entry point for Spring AI → Tencent Cloud CLS observability.
 *
 * <p>Wiring:
 * <ol>
 *     <li>Resolve {@link ClsConfig} (builder args / env vars / {@code .env})</li>
 *     <li>Build a standard {@link OtlpHttpSpanExporter} pointed at CLS's OTLP/HTTP
 *         trace endpoint, authenticated with an HTTP Basic {@code Authorization}
 *         header and routed with a {@code topic_id} header</li>
 *     <li>Assemble a {@link SdkTracerProvider} with a {@link BatchSpanProcessor}</li>
 *     <li>Build an isolated {@link OpenTelemetrySdk} (<b>not</b> registered as
 *         {@code GlobalOpenTelemetry}, to avoid colliding with the host app's own
 *         OTel setup — e.g. Spring Boot's Micrometer-Tracing bridge or ARMS agent)</li>
 *     <li>Register a JVM shutdown hook to flush pending spans</li>
 * </ol>
 *
 * <p><b>How data flows.</b> Spring AI emits Micrometer {@code Observation}s from
 * every {@code ChatClient} / {@code ChatModel} / {@code Advisor} /
 * {@code ToolCallback} / {@code EmbeddingModel} / {@code VectorStore} call. This
 * SDK ships a {@link ClsObservationHandler} that subscribes to those events and
 * writes an OTel span (through the private {@link Tracer} returned by
 * {@link #tracer()}) per observation, with CLS GenAI-Trace-compliant
 * {@code gen_ai.*} attributes.
 *
 * <p><b>Transport.</b> Spans are uploaded over the vendor-neutral OTLP/HTTP
 * protocol to CLS, not through the CLS Java SDK. All {@code io.opentelemetry.*}
 * classes are shaded into {@code com.tencentcloudapi.observability.shaded.*} so
 * this SDK's Tracer is immune to interference from an in-JVM ARMS javaagent or
 * the user's own OTel SDK.
 *
 * <pre>{@code
 * // 1. install
 * ClsObservability obs = ClsObservability.install();        // or install(config)
 *
 * // 2. register the handler onto the SAME ObservationRegistry Spring AI uses
 * //    (Spring Boot auto-wires one as a bean named observationRegistry)
 * obs.registerOn(observationRegistry);
 * }</pre>
 */
public final class ClsObservability {

    private static final Logger LOG = LoggerFactory.getLogger(ClsObservability.class);
    private static final String INSTRUMENTATION_SCOPE =
            "tencentcloud-agentobs-sdk-springai-java";

    private final SdkTracerProvider tracerProvider;
    private final SpanExporter exporter;
    private final OpenTelemetrySdk openTelemetry;
    private final Tracer tracer;
    private final ClsConfig config;

    private ClsObservability(SdkTracerProvider tracerProvider, SpanExporter exporter,
                             OpenTelemetrySdk openTelemetry, Tracer tracer, ClsConfig config) {
        this.tracerProvider = tracerProvider;
        this.exporter = exporter;
        this.openTelemetry = openTelemetry;
        this.tracer = tracer;
        this.config = config;
    }

    /**
     * Resolve configuration from env vars / {@code .env} and install.
     *
     * @throws IllegalStateException if required CLS parameters are missing.
     */
    public static ClsObservability install() {
        return install(ClsConfig.builder().build());
    }

    /** Install with an explicit {@link ClsConfig}. */
    public static ClsObservability install(ClsConfig config) {
        // Before building any OTel plumbing, flip on Reactor's automatic context
        // propagation. This lets Micrometer's ObservationThreadLocalAccessor (which
        // micrometer-observation.jar auto-registers via
        // META-INF/services/io.micrometer.context.ThreadLocalAccessor) cross Reactor
        // scheduler threads — restoring parent trace linkage for top-level
        // ChatClient.stream() / ChatModel.stream() / retrieval-advisor streaming
        // paths. Without it, Spring AI's inner observations run on worker threads
        // where getCurrentObservation() returns null and each child observation
        // becomes a new trace root.
        //
        // Reflection-only: SDK has no compile-time dependency on reactor-core.
        // If Reactor isn't on the classpath (user is on a pure sync Spring AI
        // integration), this is a safe no-op. The hook is JVM-global and
        // idempotent, so re-calling install() or coexisting with a user who
        // already enabled the hook is harmless.
        if (config.reactorContextPropagation()) {
            enableReactorContextPropagation();
        } else {
            LOG.info("[cls-springai] Reactor automatic context propagation DISABLED by "
                    + "config; top-level ChatClient.stream() / ChatModel.stream() traces "
                    + "may be fragmented. To re-enable: unset "
                    + ClsConfig.ENV_REACTOR_CONTEXT_PROPAGATION
                    + " or pass .reactorContextPropagation(true) on the builder.");
        }

        SpanExporter exporter = OtlpHttpSpanExporter.builder()
                .setEndpoint(config.otlpTracesUrl())
                .addHeader("Authorization", config.authorizationHeader())
                .addHeader("topic_id", config.topicId())
                .setTimeout(Duration.ofSeconds(10))
                .build();

        Resource resource = Resource.getDefault().merge(
                Resource.create(buildResourceAttributes(config)));

        SdkTracerProvider tracerProvider = SdkTracerProvider.builder()
                .setResource(resource)
                .setSampler(buildSampler(config))
                .addSpanProcessor(BatchSpanProcessor.builder(exporter)
                        .setMaxExportBatchSize(config.batchSize())
                        .build())
                .build();

        // 关键：build() 而不是 buildAndRegisterGlobal()。本 SDK 的 Tracer 对外部完全
        // 不可见，避免与客户进程中其他 OTel SDK / ARMS javaagent 的 GlobalOpenTelemetry
        // 竞争单例注册权；并且因 io.opentelemetry.* 已被 shade，即便外部也调用
        // GlobalOpenTelemetry 也不会解析到我们的类。
        OpenTelemetrySdk sdk = OpenTelemetrySdk.builder()
                .setTracerProvider(tracerProvider)
                .build();

        Tracer tracer = sdk.getTracer(INSTRUMENTATION_SCOPE);

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            try {
                tracerProvider.close();
            } catch (Exception e) {
                LOG.warn("Error closing tracer provider on shutdown: {}", e.getMessage());
            }
        }, "cls-springai-shutdown"));

        LOG.info("CLS observability (Spring AI, OTLP/HTTP) installed "
                        + "(service={}, endpoint={}, topic={}, sampleRatio={}). "
                        + "Next step: obs.registerOn(observationRegistry).",
                config.serviceName(), config.otlpTracesUrl(), config.topicId(),
                config.sampleRatio());

        return new ClsObservability(tracerProvider, exporter, sdk, tracer, config);
    }

    /**
     * Build the head-based {@link Sampler} for the configured sample ratio.
     *
     * <p>Wrapping the ratio sampler in {@link Sampler#parentBased} is what
     * keeps a turn's trace tree intact. Only the turn's root
     * {@code enter_application} span is a trace root; every other span this
     * SDK emits (agent / react / chat / tool / embedding / retrieval, and any
     * nested {@code invoke_subagent} subtree) is created with an explicit
     * parent, so it inherits the root's decision instead of re-rolling the
     * dice. A turn is therefore uploaded whole or not at all — CLS never
     * renders a trace with holes in it.
     *
     * <p>Because {@code traceIdRatioBased} derives its verdict from the trace
     * id, and every top-level {@code ChatClient} call starts a fresh trace id,
     * the ratio applies per turn rather than per session.
     *
     * <p>The two extremes are special-cased to the constant samplers: at
     * {@code 1.0} this is exactly the pre-sampling behaviour (OTel's default
     * is {@code ParentBased(root=AlwaysOn)}), so existing deployments see no
     * change unless they opt in.
     */
    private static Sampler buildSampler(ClsConfig config) {
        double ratio = config.sampleRatio();
        if (ratio >= 1.0d) {
            return Sampler.alwaysOn();
        }
        if (ratio <= 0.0d) {
            LOG.warn("[cls-springai] sampleRatio=0 — every trace will be dropped and "
                    + "nothing will reach CLS. Set " + ClsConfig.ENV_SAMPLE_RATIO
                    + " above 0 (or remove it) to re-enable upload.");
            return Sampler.alwaysOff();
        }
        LOG.info("[cls-springai] head-based trace sampling enabled (ratio={}); the "
                + "decision is taken once per turn and inherited by all child spans, "
                + "so sampled-out turns are dropped whole.", ratio);
        return Sampler.parentBased(Sampler.traceIdRatioBased(ratio));
    }

    /**
     * Reflectively invoke {@code reactor.core.publisher.Hooks
     * .enableAutomaticContextPropagation()}.
     *
     * <p>Reflection is deliberate: the SDK has no compile-time dependency on
     * {@code reactor-core}. In a pure-synchronous Spring AI integration
     * (no streaming, no WebFlux) reactor-core may be absent — in that case
     * this is a logged no-op rather than a {@link NoClassDefFoundError} at
     * {@link #install(ClsConfig)} time. The literal class-name string is
     * not rewritten by the maven-shade-plugin's class-relocation phase, so
     * this call remains correct in the shaded jar.
     *
     * <p>The hook is a JVM-global, idempotent side-effect. Calling it twice
     * — e.g. because the user also enables it themselves — is harmless.
     */
    private static void enableReactorContextPropagation() {
        try {
            Class<?> hooksClass = Class.forName("reactor.core.publisher.Hooks");
            hooksClass.getMethod("enableAutomaticContextPropagation").invoke(null);
            LOG.info("[cls-springai] enabled Reactor automatic context propagation "
                    + "(restores parent trace linkage for ChatClient.stream() / "
                    + "ChatModel.stream() / retrieval-advisor streaming paths).");
        } catch (ClassNotFoundException e) {
            // Pure sync Spring AI integration: Reactor isn't on the classpath.
            // The .stream() code paths that would otherwise suffer trace
            // fragmentation can't be reached anyway, so this is a no-op.
            LOG.debug("[cls-springai] reactor-core not on classpath; skipping Reactor "
                    + "automatic context propagation hook (fix is not needed in a "
                    + "pure synchronous integration).");
        } catch (Throwable t) {
            // NoSuchMethodException on an unexpectedly old Reactor, SecurityManager
            // denial of setAccessible, etc. Must never break SDK install().
            LOG.warn("[cls-springai] failed to enable Reactor automatic context "
                    + "propagation ({}); top-level ChatClient.stream() traces may be "
                    + "fragmented on CLS. To silence this warning, pass "
                    + ".reactorContextPropagation(false) on the ClsConfig builder "
                    + "or set " + ClsConfig.ENV_REACTOR_CONTEXT_PROPAGATION + "=false.",
                    t.toString());
        }
    }

    /**
     * Compose the OTLP {@link io.opentelemetry.api.common.Attributes} that go
     * into the shared {@link Resource} applied to every span this SDK emits.
     *
     * <p>Follows the CLS GenAI Trace v5 Resource schema: {@code service.*} /
     * {@code host.*} / {@code process.*} / {@code os.*} / {@code deployment.*}
     * / {@code telemetry.sdk.*}. Optional user-configured fields
     * ({@code service.version}, {@code service.instance.id},
     * {@code deployment.environment.name}) are omitted when empty so CLS
     * doesn't render an empty string field.
     *
     * <p>Auto-detected fields (host name / process pid / runtime / OS) are
     * best-effort \u2014 any failure falls back to not writing that field rather
     * than crashing the whole install() path, so a locked-down JVM (SecurityManager,
     * container with masked /proc, etc.) still bootstraps cleanly.
     */
    private static Attributes buildResourceAttributes(ClsConfig config) {
        AttributesBuilder b = Attributes.builder();

        // ── service.* (user-visible identity) ──────────────────────────────
        b.put("service.name", config.serviceName());
        if (!config.serviceVersion().isEmpty()) {
            b.put("service.version", config.serviceVersion());
        }
        if (!config.serviceInstanceId().isEmpty()) {
            b.put("service.instance.id", config.serviceInstanceId());
        } else {
            // Spec recommends a per-process service.instance.id even when the user
            // hasn't set one. Derive from the local host + pid, which is stable
            // for the JVM lifetime and distinct across replicas on the same host.
            String auto = autoServiceInstanceId();
            if (auto != null) {
                b.put("service.instance.id", auto);
            }
        }

        // ── deployment.environment.name (prod / staging / dev / etc.) ──────
        if (!config.deploymentEnvironment().isEmpty()) {
            b.put("deployment.environment.name", config.deploymentEnvironment());
        }

        // ── host.* ──────────────────────────────────────────────────────────
        String hostName = safeHostName();
        if (hostName != null) {
            b.put("host.name", hostName);
        }
        // host.ip reuses the already-detected source IP from ClsConfig if present.
        String hostIp = config.source();
        if (hostIp != null && !hostIp.isEmpty()) {
            b.put("host.ip", hostIp);
        }

        // ── process.* ───────────────────────────────────────────────────────
        try {
            b.put("process.pid", ProcessHandle.current().pid());
        } catch (Throwable ignored) { /* best-effort */ }
        b.put("process.runtime.name", "java");
        String runtimeVersion = System.getProperty("java.runtime.version");
        if (runtimeVersion != null) {
            b.put("process.runtime.version", runtimeVersion);
        }

        // ── os.* ────────────────────────────────────────────────────────────
        String osName = System.getProperty("os.name");
        if (osName != null) {
            // The OTel semconv uses lowercase 'linux' / 'darwin' / 'windows';
            // System property gives 'Linux' / 'Mac OS X' / 'Windows 11'. Keep the
            // raw value under os.description and only normalise os.type.
            b.put("os.type", normaliseOsType(osName));
            b.put("os.description", osName);
        }
        String osVersion = System.getProperty("os.version");
        if (osVersion != null) {
            b.put("os.version", osVersion);
        }

        // ── telemetry.sdk.* ─────────────────────────────────────────────────
        b.put("telemetry.sdk.language", "java");
        b.put("telemetry.sdk.name", INSTRUMENTATION_SCOPE);
        String sdkVersion = detectSdkVersion();
        if (sdkVersion != null) {
            b.put("telemetry.sdk.version", sdkVersion);
        }

        return b.build();
    }

    /** Resolve the local host's name without throwing. Falls back to {@code null}
     *  if the JVM can't reach DNS (containers / sandboxed envs). */
    private static String safeHostName() {
        try {
            return java.net.InetAddress.getLocalHost().getHostName();
        } catch (Throwable e) {
            return null;
        }
    }

    /** Derive a service.instance.id from host + pid when the user didn't set one.
     *  Format: {@code hostname@pid}, matching OTel convention examples. */
    private static String autoServiceInstanceId() {
        try {
            String host = safeHostName();
            long pid = ProcessHandle.current().pid();
            if (host != null) return host + "@" + pid;
            return "pid-" + pid;
        } catch (Throwable e) {
            return null;
        }
    }

    /** Collapse the JVM's os.name system property onto the OTel semantic
     *  convention value set (linux / darwin / windows / solaris / aix / ...). */
    private static String normaliseOsType(String osName) {
        String lower = osName.toLowerCase();
        if (lower.startsWith("linux"))    return "linux";
        if (lower.startsWith("mac"))      return "darwin";
        if (lower.startsWith("darwin"))   return "darwin";
        if (lower.startsWith("windows"))  return "windows";
        if (lower.startsWith("sunos"))    return "solaris";
        if (lower.startsWith("aix"))      return "aix";
        if (lower.startsWith("freebsd"))  return "freebsd";
        return lower;
    }

    /** Read the SDK's own Maven version from the jar manifest. Returns {@code
     *  null} if the class was loaded without a manifest (e.g. in-IDE from
     *  target/classes), in which case we skip writing telemetry.sdk.version. */
    private static String detectSdkVersion() {
        try {
            Package pkg = ClsObservability.class.getPackage();
            if (pkg != null) {
                String v = pkg.getImplementationVersion();
                if (v != null && !v.isEmpty()) return v;
            }
        } catch (Throwable ignored) { /* fall through */ }
        return null;
    }

    /** The private {@link OpenTelemetry} instance (not global). */
    public OpenTelemetry openTelemetry() { return openTelemetry; }

    public SdkTracerProvider tracerProvider() { return tracerProvider; }

    public SpanExporter exporter() { return exporter; }

    /** The {@link Tracer} that {@link ClsObservationHandler} emits spans through. */
    public Tracer tracer() { return tracer; }

    public ClsConfig config() { return config; }

    /** Flush any pending spans immediately. */
    public void flush() {
        tracerProvider.forceFlush().join(10, java.util.concurrent.TimeUnit.SECONDS);
    }

    /** Shut down the tracer provider, flushing and closing the OTLP exporter. */
    public void shutdown() {
        tracerProvider.close();
    }

    /**
     * Create a fresh {@link ClsObservationHandler} bound to this installation.
     * You usually want {@link #registerOn(ObservationRegistry)} instead, which
     * both creates and registers in one call.
     */
    public ClsObservationHandler newObservationHandler() {
        return new ClsObservationHandler(tracer, config);
    }

    /**
     * Register this installation's handler onto the given {@link ObservationRegistry}
     * — typically the Spring-managed bean named {@code observationRegistry} that
     * Spring AI emits all of its telemetry through.
     *
     * <p>Pass the <b>same</b> {@code ObservationRegistry} Spring AI uses, otherwise
     * no events will reach us. In a Spring Boot app, inject
     * {@code ObservationRegistry} and call this method once at startup.
     *
     * @return the registered handler, in case the caller wants to keep a reference.
     */
    public ClsObservationHandler registerOn(ObservationRegistry registry) {
        ClsObservationHandler handler = newObservationHandler();
        registry.observationConfig().observationHandler(handler);
        LOG.info("CLS ObservationHandler registered onto ObservationRegistry@{}",
                Integer.toHexString(System.identityHashCode(registry)));
        return handler;
    }
}
