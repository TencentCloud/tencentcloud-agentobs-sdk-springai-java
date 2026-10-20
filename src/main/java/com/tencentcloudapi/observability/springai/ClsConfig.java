package com.tencentcloudapi.observability.springai;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Resolves Tencent Cloud CLS configuration for OTLP/HTTP trace upload.
 *
 * <p>Resolution priority (highest first):
 * <ol>
 *     <li>Explicit values passed to the builder</li>
 *     <li>Process environment variables ({@code CLS_ENDPOINT} etc.)</li>
 *     <li>An optional {@code .env} file — {@code ./.env} or
 *         {@code ~/.agentobs-springai/config.env}. Existing environment
 *         variables are never overridden by the file.</li>
 * </ol>
 *
 * <p>The same four fields ({@code endpoint}/{@code topicId}/{@code secretId}/
 * {@code secretKey}) that the CLS-native SDK uses are reused here: with OTLP,
 * {@code secretId}/{@code secretKey} become the HTTP Basic {@code Authorization}
 * header and {@code topicId} becomes the {@code topic_id} header.
 */
public final class ClsConfig {

    /** Environment variable names. */
    public static final String ENV_ENDPOINT = "CLS_ENDPOINT";
    public static final String ENV_TOPIC_ID = "CLS_TOPIC_ID";
    public static final String ENV_SECRET_ID = "CLS_SECRET_ID";
    public static final String ENV_SECRET_KEY = "CLS_SECRET_KEY";
    public static final String ENV_SERVICE_NAME = "CLS_SERVICE_NAME";
    public static final String ENV_SERVICE_VERSION = "CLS_SERVICE_VERSION";
    public static final String ENV_SERVICE_INSTANCE_ID = "CLS_SERVICE_INSTANCE_ID";
    public static final String ENV_DEPLOYMENT_ENVIRONMENT = "CLS_DEPLOYMENT_ENVIRONMENT";
    public static final String ENV_SOURCE = "CLS_SOURCE";
    public static final String ENV_BATCH_SIZE = "CLS_BATCH_SIZE";
    public static final String ENV_DEBUG = "CLS_DEBUG";
    /**
     * Toggle for Reactor automatic context propagation (default on).
     *
     * <p>Spring AI's {@code ChatClient.stream()} / {@code ChatModel.stream()}
     * / streaming retrieval advisors dispatch inner Micrometer observations
     * onto Reactor scheduler threads. Those inner observations find their
     * parent through {@code ObservationThreadLocalAccessor} — a
     * {@code ThreadLocal}-backed accessor that Reactor only carries across
     * worker threads when {@code reactor.core.publisher.Hooks
     * .enableAutomaticContextPropagation()} has been called.
     *
     * <p>Without the hook, top-level {@code .stream()} calls originating from
     * a thread that already has an open parent observation (e.g. a Spring MVC
     * request thread, a user-defined subagent root span, a {@code @Scheduled}
     * tick) produce fragmented, parentless child traces on CLS. The SDK
     * enables the hook by default inside {@link ClsObservability#install};
     * flip this knob off if your application already calls the hook itself
     * or if you need to opt out for any other reason.
     *
     * <p>Env var: {@code CLS_SPRINGAI_REACTOR_CONTEXT_PROPAGATION}.
     * System property: {@code cls.springai.reactor-context-propagation}.
     * Truthy = {@code 1}/{@code true}/{@code yes} (case-insensitive);
     * anything else is treated as false.
     */
    public static final String ENV_REACTOR_CONTEXT_PROPAGATION =
            "CLS_SPRINGAI_REACTOR_CONTEXT_PROPAGATION";
    public static final String SYSPROP_REACTOR_CONTEXT_PROPAGATION =
            "cls.springai.reactor-context-propagation";
    /** Prefix for env-var-based custom attributes. Any env var starting with
     *  this prefix is converted into an {@code agent.custom.<rest>} tag written
     *  as a lowercase attribute onto every span. Example:
     *  {@code CLS_CUSTOM_PROJECT_ID=proj_001} → {@code agent.custom.project_id=proj_001}. */
    public static final String ENV_CUSTOM_PREFIX = "CLS_CUSTOM_";

    private static final String DEFAULT_SERVICE_NAME = "springai-app";
    private static final int DEFAULT_BATCH_SIZE = 32;
    private static final int MAX_BATCH_SIZE = 1000;

    private final String endpoint;
    private final String topicId;
    private final String secretId;
    private final String secretKey;
    private final String serviceName;
    /** Optional, maps to OTel Resource {@code service.version}. */
    private final String serviceVersion;
    /** Optional, maps to OTel Resource {@code service.instance.id}. */
    private final String serviceInstanceId;
    /** Optional, maps to OTel Resource {@code deployment.environment.name}. */
    private final String deploymentEnvironment;
    private final String source;
    private final int batchSize;
    private final boolean debug;
    /** When true, {@link ClsObservability#install} calls
     *  {@code reactor.core.publisher.Hooks.enableAutomaticContextPropagation()}
     *  so Micrometer's {@code ObservationThreadLocalAccessor} crosses Reactor
     *  scheduler threads — restoring parent trace linkage for top-level
     *  {@code ChatClient.stream()} / {@code ChatModel.stream()} / retrieval
     *  advisor streaming paths. See {@link #ENV_REACTOR_CONTEXT_PROPAGATION}. */
    private final boolean reactorContextPropagation;
    /** Immutable map of user-supplied custom attributes written onto every
     *  span as {@code agent.custom.<key>}. Keys are not prefixed in storage;
     *  the handler adds the prefix. Values are strings (span attributes). */
    private final Map<String, String> customAttributes;

    private ClsConfig(String endpoint, String topicId, String secretId, String secretKey,
                      String serviceName, String serviceVersion, String serviceInstanceId,
                      String deploymentEnvironment, String source, int batchSize, boolean debug,
                      boolean reactorContextPropagation,
                      Map<String, String> customAttributes) {
        this.endpoint = endpoint;
        this.topicId = topicId;
        this.secretId = secretId;
        this.secretKey = secretKey;
        this.serviceName = serviceName;
        this.serviceVersion = serviceVersion;
        this.serviceInstanceId = serviceInstanceId;
        this.deploymentEnvironment = deploymentEnvironment;
        this.source = source;
        this.batchSize = batchSize;
        this.debug = debug;
        this.reactorContextPropagation = reactorContextPropagation;
        this.customAttributes = customAttributes;
    }

    public String endpoint() { return endpoint; }
    public String topicId() { return topicId; }
    public String secretId() { return secretId; }
    public String secretKey() { return secretKey; }
    public String serviceName() { return serviceName; }
    /** @return {@code service.version} or empty string if unset. */
    public String serviceVersion() { return serviceVersion; }
    /** @return {@code service.instance.id} or empty string if unset. */
    public String serviceInstanceId() { return serviceInstanceId; }
    /** @return {@code deployment.environment.name} or empty string if unset. */
    public String deploymentEnvironment() { return deploymentEnvironment; }
    public String source() { return source; }
    public int batchSize() { return batchSize; }
    public boolean debug() { return debug; }
    /** @return whether {@link ClsObservability#install} should enable
     *  {@code reactor.core.publisher.Hooks.enableAutomaticContextPropagation()}
     *  at startup. Default {@code true}; opt out via builder, env var
     *  {@link #ENV_REACTOR_CONTEXT_PROPAGATION}, or system property
     *  {@link #SYSPROP_REACTOR_CONTEXT_PROPAGATION}. */
    public boolean reactorContextPropagation() { return reactorContextPropagation; }
    /** @return immutable map of user-supplied custom attributes; keys are the
     *  raw user names (not yet prefixed with {@code agent.custom.}). */
    public Map<String, String> customAttributes() { return customAttributes; }

    /**
     * Build the {@code Authorization} header value for CLS's OTLP/HTTP trace
     * endpoint: {@code Basic base64(SecretId:SecretKey)}.
     */
    public String authorizationHeader() {
        String raw = secretId + ":" + secretKey;
        String encoded = java.util.Base64.getEncoder()
                .encodeToString(raw.getBytes(StandardCharsets.UTF_8));
        return "Basic " + encoded;
    }

    /**
     * Full OTLP/HTTP traces URL for CLS: {@code <endpoint>/v1/traces}.
     */
    public String otlpTracesUrl() {
        String base = endpoint;
        while (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        return base + "/v1/traces";
    }

    public static Builder builder() {
        return new Builder();
    }

    /** Fluent builder that layers explicit values over env vars and a {@code .env} file. */
    public static final class Builder {
        private String endpoint;
        private String topicId;
        private String secretId;
        private String secretKey;
        private String serviceName;
        private String serviceVersion;
        private String serviceInstanceId;
        private String deploymentEnvironment;
        private String source;
        private Integer batchSize;
        private Boolean debug;
        private Boolean reactorContextPropagation;
        private final Map<String, String> customAttributes = new LinkedHashMap<>();

        public Builder endpoint(String endpoint) { this.endpoint = endpoint; return this; }
        public Builder topicId(String topicId) { this.topicId = topicId; return this; }
        public Builder secretId(String secretId) { this.secretId = secretId; return this; }
        public Builder secretKey(String secretKey) { this.secretKey = secretKey; return this; }
        public Builder serviceName(String serviceName) { this.serviceName = serviceName; return this; }
        public Builder serviceVersion(String serviceVersion) { this.serviceVersion = serviceVersion; return this; }
        public Builder serviceInstanceId(String serviceInstanceId) { this.serviceInstanceId = serviceInstanceId; return this; }
        public Builder deploymentEnvironment(String deploymentEnvironment) { this.deploymentEnvironment = deploymentEnvironment; return this; }
        public Builder source(String source) { this.source = source; return this; }
        public Builder batchSize(Integer batchSize) { this.batchSize = batchSize; return this; }
        public Builder debug(Boolean debug) { this.debug = debug; return this; }
        /** Opt in/out of the Reactor automatic context propagation hook that
         *  {@link ClsObservability#install} calls at startup. Default
         *  {@code true}; set to {@code false} if you already enable the hook
         *  yourself or need to opt out. See {@link ClsConfig#reactorContextPropagation()}. */
        public Builder reactorContextPropagation(Boolean reactorContextPropagation) {
            this.reactorContextPropagation = reactorContextPropagation;
            return this;
        }

        /** Add a single custom attribute. Written on every span as
         *  {@code agent.custom.<key>}. Null or empty keys/values are ignored. */
        public Builder customAttribute(String key, String value) {
            if (key != null && !key.isEmpty() && value != null && !value.isEmpty()) {
                customAttributes.put(key, value);
            }
            return this;
        }

        /** Add multiple custom attributes at once. Existing entries are
         *  preserved; new keys with the same name overwrite. */
        public Builder customAttributes(Map<String, String> attrs) {
            if (attrs != null) {
                for (Map.Entry<String, String> e : attrs.entrySet()) {
                    customAttribute(e.getKey(), e.getValue());
                }
            }
            return this;
        }

        public ClsConfig build() {
            Map<String, String> envFile = loadEnvFile();

            String resolvedEndpoint = normalizeEndpoint(
                    resolve(endpoint, ENV_ENDPOINT, envFile, ""));
            String resolvedTopicId = resolve(topicId, ENV_TOPIC_ID, envFile, "");
            String resolvedSecretId = resolve(secretId, ENV_SECRET_ID, envFile, "");
            String resolvedSecretKey = resolve(secretKey, ENV_SECRET_KEY, envFile, "");
            String resolvedServiceName = resolve(serviceName, ENV_SERVICE_NAME, envFile,
                    DEFAULT_SERVICE_NAME);
            String resolvedServiceVersion = resolve(serviceVersion, ENV_SERVICE_VERSION, envFile, "");
            String resolvedServiceInstanceId = resolve(serviceInstanceId, ENV_SERVICE_INSTANCE_ID,
                    envFile, "");
            String resolvedDeploymentEnvironment = resolve(deploymentEnvironment,
                    ENV_DEPLOYMENT_ENVIRONMENT, envFile, "");
            String resolvedSource = resolve(source, ENV_SOURCE, envFile, "");
            if (resolvedSource.isEmpty()) {
                resolvedSource = detectLocalIp();
            }

            int resolvedBatchSize = DEFAULT_BATCH_SIZE;
            if (batchSize != null) {
                resolvedBatchSize = batchSize;
            } else {
                String raw = resolve(null, ENV_BATCH_SIZE, envFile, "");
                if (!raw.isEmpty()) {
                    try {
                        resolvedBatchSize = Integer.parseInt(raw.trim());
                    } catch (NumberFormatException ignored) {
                        resolvedBatchSize = DEFAULT_BATCH_SIZE;
                    }
                }
            }
            resolvedBatchSize = Math.max(1, Math.min(resolvedBatchSize, MAX_BATCH_SIZE));

            boolean resolvedDebug;
            if (debug != null) {
                resolvedDebug = debug;
            } else {
                String raw = resolve(null, ENV_DEBUG, envFile, "").toLowerCase();
                resolvedDebug = raw.equals("1") || raw.equals("true") || raw.equals("yes");
            }

            // Reactor context-propagation hook: resolution order is
            //   builder explicit > system property > env var > .env file > default true.
            // System property wins over env var so a user doing `-Dcls.springai
            // .reactor-context-propagation=false` on a shared machine can
            // override an inherited env var without re-exporting it.
            boolean resolvedReactorPropagation;
            if (reactorContextPropagation != null) {
                resolvedReactorPropagation = reactorContextPropagation;
            } else {
                String raw = System.getProperty(SYSPROP_REACTOR_CONTEXT_PROPAGATION);
                if (raw == null || raw.isEmpty()) {
                    raw = resolve(null, ENV_REACTOR_CONTEXT_PROPAGATION, envFile, "");
                }
                raw = raw.trim().toLowerCase();
                if (raw.isEmpty()) {
                    resolvedReactorPropagation = true;   // default ON
                } else {
                    resolvedReactorPropagation = raw.equals("1")
                            || raw.equals("true") || raw.equals("yes");
                }
            }

            StringBuilder missing = new StringBuilder();
            if (resolvedEndpoint.isEmpty()) missing.append("\n    ").append(ENV_ENDPOINT);
            if (resolvedTopicId.isEmpty()) missing.append("\n    ").append(ENV_TOPIC_ID);
            if (resolvedSecretId.isEmpty()) missing.append("\n    ").append(ENV_SECRET_ID);
            if (resolvedSecretKey.isEmpty()) missing.append("\n    ").append(ENV_SECRET_KEY);
            if (missing.length() > 0) {
                throw new IllegalStateException(
                        "CLS configuration incomplete, missing required field(s):" + missing
                                + "\n\n  Provide them via one of:\n"
                                + "    1. Environment variables: export CLS_ENDPOINT=... "
                                + "CLS_TOPIC_ID=... CLS_SECRET_ID=... CLS_SECRET_KEY=...\n"
                                + "    2. A .env file (./.env or ~/.agentobs-springai/config.env)\n"
                                + "    3. Builder args: ClsConfig.builder().endpoint(...)"
                                + ".topicId(...).secretId(...).secretKey(...).build()");
            }

            return new ClsConfig(resolvedEndpoint, resolvedTopicId, resolvedSecretId,
                    resolvedSecretKey, resolvedServiceName, resolvedServiceVersion,
                    resolvedServiceInstanceId, resolvedDeploymentEnvironment, resolvedSource,
                    resolvedBatchSize, resolvedDebug, resolvedReactorPropagation,
                    resolveCustomAttributes(envFile));
        }

        /** Fuse builder-supplied customAttributes with any env-var-based ones.
         *  Builder args win on key collision. Env vars matching the
         *  {@link #ENV_CUSTOM_PREFIX} prefix are converted to lowercase keys,
         *  dropping the prefix (e.g. {@code CLS_CUSTOM_PROJECT_ID} →
         *  {@code project_id}). .env file entries are consulted too but
         *  process env wins over file. */
        private Map<String, String> resolveCustomAttributes(Map<String, String> envFile) {
            Map<String, String> merged = new LinkedHashMap<>();
            // .env file first (lowest priority).
            for (Map.Entry<String, String> e : envFile.entrySet()) {
                String k = e.getKey();
                if (k != null && k.startsWith(ENV_CUSTOM_PREFIX) && e.getValue() != null
                        && !e.getValue().isEmpty()) {
                    merged.put(k.substring(ENV_CUSTOM_PREFIX.length()).toLowerCase(),
                            e.getValue());
                }
            }
            // Process env overrides .env file.
            for (Map.Entry<String, String> e : System.getenv().entrySet()) {
                String k = e.getKey();
                if (k != null && k.startsWith(ENV_CUSTOM_PREFIX) && e.getValue() != null
                        && !e.getValue().isEmpty()) {
                    merged.put(k.substring(ENV_CUSTOM_PREFIX.length()).toLowerCase(),
                            e.getValue());
                }
            }
            // Builder args win on key collision (highest priority).
            merged.putAll(customAttributes);
            return Collections.unmodifiableMap(merged);
        }
    }

    // ── resolution helpers ────────────────────────────────────────────────

    private static String resolve(String explicit, String envKey,
                                  Map<String, String> envFile, String defaultValue) {
        if (explicit != null && !explicit.isEmpty()) {
            return explicit;
        }
        String fromEnv = System.getenv(envKey);
        if (fromEnv != null && !fromEnv.isEmpty()) {
            return fromEnv;
        }
        String fromFile = envFile.get(envKey);
        if (fromFile != null && !fromFile.isEmpty()) {
            return fromFile;
        }
        return defaultValue;
    }

    private static String normalizeEndpoint(String endpoint) {
        if (endpoint == null || endpoint.isEmpty()) {
            return "";
        }
        if (endpoint.contains("://")) {
            return endpoint;
        }
        return "https://" + endpoint;
    }

    private static Map<String, String> loadEnvFile() {
        Map<String, String> result = new LinkedHashMap<>();
        for (Path candidate : candidatePaths()) {
            if (candidate == null || !Files.isRegularFile(candidate)) {
                continue;
            }
            try (BufferedReader reader = Files.newBufferedReader(candidate, StandardCharsets.UTF_8)) {
                String line;
                while ((line = reader.readLine()) != null) {
                    parseLine(line, result);
                }
                return result;
            } catch (IOException ignored) {
                // Try the next candidate.
            }
        }
        return result;
    }

    private static Path[] candidatePaths() {
        Path cwdEnv = Paths.get(System.getProperty("user.dir", "."), ".env");
        String home = System.getProperty("user.home");
        Path homeEnv = home != null
                ? Paths.get(home, ".agentobs-springai", "config.env")
                : null;
        return new Path[]{cwdEnv, homeEnv};
    }

    private static void parseLine(String line, Map<String, String> out) {
        String stripped = line.trim();
        if (stripped.isEmpty() || stripped.startsWith("#") || !stripped.contains("=")) {
            return;
        }
        int eq = stripped.indexOf('=');
        String key = stripped.substring(0, eq).trim();
        if (key.isEmpty()) {
            return;
        }
        String value = stripped.substring(eq + 1).trim();
        if (value.length() >= 2
                && value.charAt(0) == value.charAt(value.length() - 1)
                && (value.charAt(0) == '\'' || value.charAt(0) == '"')) {
            value = value.substring(1, value.length() - 1);
        }
        out.put(key, value);
    }

    private static String detectLocalIp() {
        try {
            return java.net.InetAddress.getLocalHost().getHostAddress();
        } catch (Exception e) {
            return "127.0.0.1";
        }
    }
}
