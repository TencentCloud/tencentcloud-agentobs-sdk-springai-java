package com.tencentcloudapi.observability.springai;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * Pins the resolution contract of the {@code CLS_SAMPLE_RATIO} knob.
 *
 * <p>Only the builder-explicit and system-property paths are asserted here.
 * Both of those out-rank process env vars and the {@code .env} file, so the
 * assertions stay deterministic on a developer machine that happens to have
 * {@code ~/.agentobs-springai/config.env} or exported {@code CLS_*} vars.
 * Credentials are passed explicitly so {@link ClsConfig.Builder#build()}
 * doesn't fail its required-field check.
 */
class ClsConfigSampleRatioTest {

    /** Stand-in credentials — {@code build()} only checks for non-emptiness. */
    private static ClsConfig.Builder withCreds() {
        return ClsConfig.builder()
                .endpoint("https://ap-guangzhou.cls.tencentcs.com")
                .topicId("topic-test")
                .secretId("id-test")
                .secretKey("key-test");
    }

    @AfterEach
    void clearSysProp() {
        System.clearProperty(ClsConfig.SYSPROP_SAMPLE_RATIO);
    }

    @Test
    void builderValueIsUsedVerbatim() {
        assertEquals(0.1d, withCreds().sampleRatio(0.1d).build().sampleRatio(), 1e-9);
    }

    @Test
    void outOfRangeValuesAreClamped() {
        assertEquals(1.0d, withCreds().sampleRatio(2.5d).build().sampleRatio(), 1e-9);
        assertEquals(0.0d, withCreds().sampleRatio(-1.0d).build().sampleRatio(), 1e-9);
    }

    @Test
    void nanFallsBackToKeepEverything() {
        assertEquals(1.0d, withCreds().sampleRatio(Double.NaN).build().sampleRatio(), 1e-9);
    }

    @Test
    void systemPropertyIsHonouredWhenBuilderIsSilent() {
        System.setProperty(ClsConfig.SYSPROP_SAMPLE_RATIO, "0.25");
        assertEquals(0.25d, withCreds().build().sampleRatio(), 1e-9);
    }

    @Test
    void unparseableSystemPropertyFallsBackToKeepEverything() {
        System.setProperty(ClsConfig.SYSPROP_SAMPLE_RATIO, "not-a-number");
        assertEquals(1.0d, withCreds().build().sampleRatio(), 1e-9);
    }

    @Test
    void builderBeatsSystemProperty() {
        System.setProperty(ClsConfig.SYSPROP_SAMPLE_RATIO, "0.25");
        assertEquals(0.75d, withCreds().sampleRatio(0.75d).build().sampleRatio(), 1e-9);
    }
}
