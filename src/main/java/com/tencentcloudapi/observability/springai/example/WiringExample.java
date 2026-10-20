package com.tencentcloudapi.observability.springai.example;

import com.tencentcloudapi.observability.springai.ClsConfig;
import com.tencentcloudapi.observability.springai.ClsObservability;
import io.micrometer.observation.ObservationRegistry;

/**
 * Minimal wiring example for the Spring AI → Tencent Cloud CLS observability SDK.
 *
 * <p>In a real Spring Boot application the {@link ObservationRegistry} would be
 * autowired as a bean, and this snippet would live inside an
 * {@code @Configuration} class or a {@code CommandLineRunner}. Here we simulate
 * it with a plain {@code main} method so you can see end-to-end wiring at a
 * glance.
 *
 * <p>Required environment variables (or {@code ./.env}):
 * <pre>
 *   CLS_ENDPOINT   = https://&lt;region&gt;.cls.tencentcs.com
 *   CLS_TOPIC_ID   = &lt;your-topic-id&gt;
 *   CLS_SECRET_ID  = &lt;your-secret-id&gt;
 *   CLS_SECRET_KEY = &lt;your-secret-key&gt;
 * </pre>
 */
public final class WiringExample {

    public static void main(String[] args) {
        // 1. Install the SDK. Reads CLS credentials from env vars / .env file
        //    unless you pass an explicit ClsConfig here.
        ClsObservability obs = ClsObservability.install(
                ClsConfig.builder()
                        .serviceName("my-springai-app")
                        .debug(true)                 // flip to false in prod
                        .build());

        // 2. Grab the SAME ObservationRegistry Spring AI uses. In Spring Boot:
        //        @Autowired ObservationRegistry observationRegistry;
        //    Here we create a fresh one just to show the registration step.
        ObservationRegistry registry = ObservationRegistry.create();

        // 3. Register our handler. From this point on every Spring AI
        //    observation (ChatClient / ChatModel / Advisor / Tool / Embedding /
        //    VectorStore) will be mirrored to CLS as an OTel span.
        obs.registerOn(registry);

        // 4. ... your Spring AI code runs normally, using this ObservationRegistry.
        //    When the JVM exits, the shutdown hook flushes pending spans.
        //    Call obs.flush() explicitly if you need to flush mid-run.

        obs.shutdown();
    }

    private WiringExample() { /* utility */ }
}
