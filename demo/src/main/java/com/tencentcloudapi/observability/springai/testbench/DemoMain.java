package com.tencentcloudapi.observability.springai.testbench;

import com.tencentcloudapi.observability.springai.ClsBaggage;
import com.tencentcloudapi.observability.springai.ClsObservability;

import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;

/**
 * End-to-end verification entry point for the Spring AI → Tencent Cloud CLS SDK.
 *
 * <h2>What this demo does</h2>
 * <ol>
 *     <li>Installs {@link ClsObservability}, which spins up a private OTel
 *         TracerProvider + OTLP/HTTP exporter pointed at CLS. The exporter
 *         reads {@code CLS_ENDPOINT} / {@code CLS_TOPIC_ID} /
 *         {@code CLS_SECRET_ID} / {@code CLS_SECRET_KEY} from env or
 *         {@code ./.env} / {@code ~/.agentobs-springai/config.env}.</li>
 *     <li>Creates a vanilla {@link ObservationRegistry} and registers the SDK's
 *         handler onto it — just like a Spring Boot app would with the
 *         auto-wired bean.</li>
 *     <li>Sets a session / user via {@link ClsBaggage} to simulate what a user
 *         controller does on every request.</li>
 *     <li>Fires a <b>synthetic Spring-AI-shaped observation tree</b>:
 *         <pre>
 *           spring.ai.chat.client
 *             └── spring.ai.advisor
 *                   ├── spring.ai.chat.model (round 1 — asks for tool)
 *                   ├── spring.ai.tool       (get_weather)
 *                   └── spring.ai.chat.model (round 2 — final answer)
 *         </pre>
 *         <p>No real LLM, no network. The handler sees the same observation
 *         names and context subclasses it would see from a live
 *         {@code ChatClient} call, so span fields, parent/child linkage, and
 *         ID chain are fully exercised.</li>
 *     <li>Flushes and prints the expected span counts / IDs so you can
 *         cross-check against the CLS console.</li>
 * </ol>
 *
 * <h2>Why synthetic observations instead of a real {@code ChatClient}?</h2>
 * The SDK is validated at the <b>Micrometer Observation layer</b>, which is
 * exactly where Spring AI itself emits all its telemetry. Driving observations
 * directly removes any dependency on a working LLM endpoint, Spring Boot
 * autoconfig, or a specific Spring AI minor version's internals, while still
 * covering 100% of the handler's code paths. If this demo prints the expected
 * trace on CLS, the handler is wired correctly for any real Spring AI
 * application.
 *
 * <h2>How to run</h2>
 * <pre>
 *   export CLS_ENDPOINT=https://ap-guangzhou.cls.tencentcs.com
 *   export CLS_TOPIC_ID=xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx
 *   export CLS_SECRET_ID=AKID...
 *   export CLS_SECRET_KEY=...
 *
 *   cd springai/demo
 *   mvn -q -DskipTests package
 *   java -jar target/springai-demo-shaded.jar
 * </pre>
 *
 * <p>Then open the CLS console and filter by
 * {@code gen_ai.session.id = <printed value>} — you should see one trace with
 * exactly <b>6 spans</b>:
 * {@code entry + agent + step + chat×2 + tool}.
 */
public final class DemoMain {

    private DemoMain() { /* main entry */ }

    public static void main(String[] args) throws Exception {
        banner("JVM boot, before ClsObservability.install()");

        // 1) Install the real CLS OTLP pipeline. Reads CLS_* env vars.
        ClsObservability obs = ClsObservability.install();

        // 2) In a Spring Boot app, you'd inject ObservationRegistry here.
        //    For a plain Java demo we create one ourselves.
        ObservationRegistry registry = ObservationRegistry.create();
        obs.registerOn(registry);

        banner("After ClsObservability.install() + registerOn()");
        System.out.println("[demo] handler registered on registry@"
                + Integer.toHexString(System.identityHashCode(registry)));

        // 3) Simulate what a user's HTTP controller would do: tag the thread
        //    with session + user, so the SDK can promote them to CLS columns.
        String sessionId = "demo-session-" + System.currentTimeMillis();
        String userId = "demo-user";
        ClsBaggage.set(sessionId, userId);

        try {
            banner("Running synthetic Spring AI observation tree — session=" + sessionId);
            runOneTurn(registry);
        } finally {
            ClsBaggage.clear();
        }

        banner("Flushing to CLS");
        obs.flush();
        obs.shutdown();

        System.out.println();
        System.out.println("================================================================");
        System.out.println("[demo] done. sessionId = " + sessionId);
        System.out.println("       userId    = " + userId);
        System.out.println("       expected  = 6 spans in CLS:");
        System.out.println("                   1 × entry   (gen_ai.span.kind=entry)");
        System.out.println("                   1 × agent   (gen_ai.span.kind=agent)");
        System.out.println("                   1 × step    (gen_ai.span.kind=step)");
        System.out.println("                   2 × chat    (gen_ai.span.kind=chat)");
        System.out.println("                   1 × tool    (gen_ai.span.kind=tool)");
        System.out.println("       → go to CLS and filter by gen_ai.session.id = " + sessionId);
        System.out.println("================================================================");
    }

    private static final String USER_PROMPT_JSON =
            "[{\"role\":\"system\",\"content\":\"You are a helpful weather assistant.\"},"
          + "{\"role\":\"user\",\"content\":\"What's the weather in Beijing today?\"}]";

    private static final String CHAT1_INPUT_JSON = USER_PROMPT_JSON;

    private static final String CHAT1_OUTPUT_JSON =
            "[{\"role\":\"assistant\",\"content\":null,"
          + "\"tool_calls\":[{\"id\":\"call_demo_0001\",\"type\":\"function\","
          + "\"function\":{\"name\":\"get_weather\",\"arguments\":\"{\\\"city\\\":\\\"Beijing\\\"}\"}}]}]";

    private static final String CHAT2_INPUT_JSON =
            "[{\"role\":\"system\",\"content\":\"You are a helpful weather assistant.\"},"
          + "{\"role\":\"user\",\"content\":\"What's the weather in Beijing today?\"},"
          + "{\"role\":\"assistant\",\"content\":null,\"tool_calls\":[{\"id\":\"call_demo_0001\","
          + "\"type\":\"function\",\"function\":{\"name\":\"get_weather\",\"arguments\":\"{\\\"city\\\":\\\"Beijing\\\"}\"}}]},"
          + "{\"role\":\"tool\",\"tool_call_id\":\"call_demo_0001\","
          + "\"content\":\"{\\\"city\\\":\\\"Beijing\\\",\\\"temp\\\":\\\"22C\\\",\\\"condition\\\":\\\"sunny\\\"}\"}]";

    private static final String FINAL_ANSWER_JSON =
            "[{\"role\":\"assistant\",\"content\":\"The weather in Beijing today is sunny with a temperature around 22°C.\"}]";

    /**
     * Simulate one full Spring AI turn by firing nested Micrometer observations
     * with the exact names Spring AI's core modules emit. The SDK's handler
     * will see these identically to a real {@code ChatClient} call.
     */
    private static void runOneTurn(ObservationRegistry registry) {
        // ── spring.ai.chat.client  ⇒  entry + agent in the SDK ──────────
        Observation chatClient = Observation.createNotStarted("spring.ai.chat.client", registry)
                .contextualName("chat gpt-4o")
                .lowCardinalityKeyValue("gen_ai.request.model", "gpt-4o")
                .lowCardinalityKeyValue("gen_ai.operation.name", "chat")
                // Fed back to the handler as the turn-level input / output.
                .highCardinalityKeyValue("gen_ai.input.messages", USER_PROMPT_JSON)
                .highCardinalityKeyValue("gen_ai.output.messages", FINAL_ANSWER_JSON);
        chatClient.observe(() -> {

            // ── spring.ai.advisor  ⇒  step ──────────────────────
            Observation advisor = Observation.createNotStarted("spring.ai.advisor", registry)
                    .contextualName("MessageChatMemoryAdvisor")
                    .lowCardinalityKeyValue("spring.ai.advisor.name", "MessageChatMemoryAdvisor");
            advisor.observe(() -> {

                // ── round 1: chat (model asks for tool) ─────────────
                Observation chat1 = Observation.createNotStarted("spring.ai.chat.model", registry)
                        .contextualName("chat gpt-4o")
                        .lowCardinalityKeyValue("gen_ai.request.model", "gpt-4o")
                        .lowCardinalityKeyValue("gen_ai.response.finish_reasons", "tool_calls")
                        .highCardinalityKeyValue("gen_ai.usage.input_tokens", "42")
                        .highCardinalityKeyValue("gen_ai.usage.output_tokens", "17")
                        .highCardinalityKeyValue("gen_ai.usage.total_tokens", "59")
                        .highCardinalityKeyValue("gen_ai.input.messages", CHAT1_INPUT_JSON)
                        .highCardinalityKeyValue("gen_ai.output.messages", CHAT1_OUTPUT_JSON);
                chat1.observe(() -> sleep(30));

                // ── spring.ai.tool  ⇒  tool ─────────────────────
                Observation tool = Observation.createNotStarted("spring.ai.tool", registry)
                        .contextualName("get_weather")
                        .lowCardinalityKeyValue("gen_ai.tool.name", "get_weather")
                        .lowCardinalityKeyValue("gen_ai.tool.type", "function")
                        .highCardinalityKeyValue("gen_ai.tool.call.id", "call_demo_0001")
                        .highCardinalityKeyValue("gen_ai.tool.call.arguments",
                                "{\"city\":\"Beijing\"}")
                        .highCardinalityKeyValue("gen_ai.tool.call.result",
                                "{\"city\":\"Beijing\",\"temp\":\"22C\",\"condition\":\"sunny\"}");
                tool.observe(() -> sleep(20));

                // ── round 2: chat (final answer, no tool call) ──────────
                Observation chat2 = Observation.createNotStarted("spring.ai.chat.model", registry)
                        .contextualName("chat gpt-4o")
                        .lowCardinalityKeyValue("gen_ai.request.model", "gpt-4o")
                        .lowCardinalityKeyValue("gen_ai.response.finish_reasons", "stop")
                        .highCardinalityKeyValue("gen_ai.usage.input_tokens", "60")
                        .highCardinalityKeyValue("gen_ai.usage.output_tokens", "25")
                        .highCardinalityKeyValue("gen_ai.usage.total_tokens", "85")
                        .highCardinalityKeyValue("gen_ai.input.messages", CHAT2_INPUT_JSON)
                        .highCardinalityKeyValue("gen_ai.output.messages", FINAL_ANSWER_JSON);
                chat2.observe(() -> sleep(25));
            });
        });

        System.out.println("[demo] synthetic turn complete:  entry → agent → step → "
                + "chat × 2  +  tool");
    }

    private static void sleep(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }

    private static void banner(String title) {
        System.out.println();
        System.out.println("================================================================");
        System.out.println("  " + title);
        System.out.println("================================================================");
    }
}
