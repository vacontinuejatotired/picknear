package com.hmdp.agent.config;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * DagProperties 配置类测试。
 * <p>
 * 覆盖默认值、getter/setter、getEffectiveMaxRetries 方法。
 * 纯 POJO，直接 new，不启动 Spring 容器。
 */
class DagPropertiesTest {

    // ═══════════════════════════════════════════════════════
    // 默认值测试
    // ═══════════════════════════════════════════════════════

    @Test
    void should_use_default_values() {
        DagProperties props = new DagProperties();

        assertThat(props.getDefaultMaxRetries()).as("默认最大重试次数").isEqualTo(3);
        assertThat(props.getDefaultRetryEnabled()).as("默认启用重试").isTrue();
        assertThat(props.getDefaultRetryBaseDelayMs()).as("默认重试延迟").isEqualTo(1000);
        assertThat(props.getLayerTimeoutSeconds()).as("默认层级超时").isEqualTo(30);
        assertThat(props.getToolTimeoutSeconds()).as("默认工具超时").isEqualTo(10);
    }

    // ═══════════════════════════════════════════════════════
    // getEffectiveMaxRetries() 测试
    // ═══════════════════════════════════════════════════════

    @Test
    void effective_max_retries_should_use_tool_config_when_set() {
        DagProperties props = new DagProperties();
        props.setDefaultMaxRetries(3);

        assertThat(props.getEffectiveMaxRetries(5)).as("工具级配置优先").isEqualTo(5);
    }

    @Test
    void effective_max_retries_should_use_global_when_tool_not_set() {
        DagProperties props = new DagProperties();
        props.setDefaultMaxRetries(3);
        props.setDefaultRetryEnabled(true);

        assertThat(props.getEffectiveMaxRetries(-1)).as("使用全局配置").isEqualTo(3);
    }

    @Test
    void effective_max_retries_should_return_zero_when_retry_disabled() {
        DagProperties props = new DagProperties();
        props.setDefaultRetryEnabled(false);

        assertThat(props.getEffectiveMaxRetries(-1)).as("禁用重试时返回 0").isEqualTo(0);
    }

    // ═══════════════════════════════════════════════════════
    // getToolTimeout() 测试
    // ═══════════════════════════════════════════════════════

    @Test
    void tool_timeout_should_use_default_when_not_configured() {
        DagProperties props = new DagProperties();
        props.setToolTimeoutSeconds(10);

        assertThat(props.getToolTimeout("queryWeather")).as("使用默认超时").isEqualTo(10);
    }

    @Test
    void tool_timeout_should_use_specific_config_when_set() {
        DagProperties props = new DagProperties();
        props.setToolTimeoutSeconds(10);
        props.setToolTimeouts(java.util.Map.of("queryWeather", 15));

        assertThat(props.getToolTimeout("queryWeather")).as("使用工具级超时").isEqualTo(15);
    }

    // ═══════════════════════════════════════════════════════
    // RetryProperties 测试
    // ═══════════════════════════════════════════════════════

    @Test
    void retry_properties_should_use_default_values() {
        DagProperties.RetryProperties retry = new DagProperties.RetryProperties();

        assertThat(retry.getStrategy()).as("默认策略").isEqualTo("exponential");
        assertThat(retry.isEnabled()).as("默认启用").isTrue();
        assertThat(retry.getBaseDelayMs()).as("默认延迟").isEqualTo(1000);
        assertThat(retry.getRetryableErrors()).as("默认可重试异常列表").isEmpty();
    }
}
