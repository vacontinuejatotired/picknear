package com.hmdp.agent.model;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ToolMetadata 异常重试相关逻辑测试。
 * <p>
 * 覆盖 isRetryable()、getEffectiveMaxRetries() 方法的各种场景。
 * 纯 POJO，直接 new，不启动 Spring 容器。
 */
class ToolMetadataTest {

    // ═══════════════════════════════════════════════════════
    // isRetryable() 测试
    // ═══════════════════════════════════════════════════════

    @Test
    void idempotent_tool_should_be_retryable() {
        ToolMetadata meta = ToolMetadata.builder()
            .name("queryWeather")
            .idempotent(true)
            .build();

        assertThat(meta.isRetryable(false)).as("幂等操作非超时场景可重试").isTrue();
        assertThat(meta.isRetryable(true)).as("幂等操作超时场景可重试").isTrue();
    }

    @Test
    void non_idempotent_tool_should_not_be_retryable() {
        ToolMetadata meta = ToolMetadata.builder()
            .name("deductBalance")
            .idempotent(false)
            .build();

        assertThat(meta.isRetryable(false)).as("非幂等操作非超时场景不可重试").isFalse();
        assertThat(meta.isRetryable(true)).as("非幂等操作超时场景不可重试（默认跟随幂等性）").isFalse();
    }

    @Test
    void max_retries_zero_should_disable_retry() {
        ToolMetadata meta = ToolMetadata.builder()
            .name("cancelOrder")
            .idempotent(true)
            .maxRetries(0)
            .build();

        assertThat(meta.isRetryable(false)).as("maxRetries=0 禁止重试").isFalse();
        assertThat(meta.isRetryable(true)).as("maxRetries=0 超时也禁止重试").isFalse();
    }

    @Test
    void retry_on_timeout_disabled_should_not_retry() {
        ToolMetadata meta = ToolMetadata.builder()
            .name("deductBalance")
            .idempotent(false)
            .retryOnTimeout(0)
            .build();

        assertThat(meta.isRetryable(true)).as("retryOnTimeout=0 超时不可重试").isFalse();
    }

    @Test
    void retry_on_timeout_enabled_should_retry() {
        ToolMetadata meta = ToolMetadata.builder()
            .name("writeData")
            .idempotent(false)
            .retryOnTimeout(1)
            .build();

        assertThat(meta.isRetryable(true)).as("retryOnTimeout=1 超时可重试").isTrue();
        assertThat(meta.isRetryable(false)).as("retryOnTimeout=1 非超时仍不可重试").isFalse();
    }

    @Test
    void retry_on_timeout_default_should_follow_idempotent() {
        ToolMetadata meta = ToolMetadata.builder()
            .name("queryWeather")
            .idempotent(true)
            .retryOnTimeout(-1)
            .build();

        assertThat(meta.isRetryable(true)).as("retryOnTimeout=-1 跟随幂等性").isTrue();
    }

    // ═══════════════════════════════════════════════════════
    // getEffectiveMaxRetries() 测试
    // ═══════════════════════════════════════════════════════

    @Test
    void tool_level_config_should_override_global() {
        ToolMetadata meta = ToolMetadata.builder()
            .name("queryWeather")
            .maxRetries(5)
            .build();

        assertThat(meta.getEffectiveMaxRetries(3)).as("工具级配置优先").isEqualTo(5);
    }

    @Test
    void global_config_should_be_used_when_tool_not_set() {
        ToolMetadata meta = ToolMetadata.builder()
            .name("queryWeather")
            .maxRetries(-1)
            .build();

        assertThat(meta.getEffectiveMaxRetries(3)).as("使用全局配置").isEqualTo(3);
    }

    @Test
    void zero_max_retries_should_return_zero() {
        ToolMetadata meta = ToolMetadata.builder()
            .name("cancelOrder")
            .maxRetries(0)
            .build();

        assertThat(meta.getEffectiveMaxRetries(3)).as("maxRetries=0 返回 0").isEqualTo(0);
    }
}
