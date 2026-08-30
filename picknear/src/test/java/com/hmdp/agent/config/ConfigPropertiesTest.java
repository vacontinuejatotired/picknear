package com.hmdp.agent.config;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Config 配置类值对象测试。
 * <p>
 * 覆盖 SubTaskProperties、FeatureProperties、PromptGuardProperties 的默认值和 setter/getter。
 * 纯 POJO，直接 new，不启动 Spring 容器。
 */
class ConfigPropertiesTest {

    // ═══════════════════════════════════════════════════════
    // SubTaskProperties
    // ═══════════════════════════════════════════════════════

    @Test
    void subtask_should_use_default_values() {
        SubTaskProperties props = new SubTaskProperties();

        assertThat(props.getMaxRetries()).as("默认重试 3 次").isEqualTo(3);
        assertThat(props.getTimeout()).as("默认单次超时 30s").isEqualTo(Duration.ofSeconds(30));
        assertThat(props.getTotalTimeout()).as("默认总超时 60s").isEqualTo(Duration.ofSeconds(60));
        assertThat(props.getRetryBackoff()).as("默认退避 1s").isEqualTo(Duration.ofSeconds(1));
    }

    @Test
    void subtask_should_accept_custom_values() {
        SubTaskProperties props = new SubTaskProperties();
        props.setMaxRetries(5);
        props.setTotalTimeout(Duration.ofSeconds(120));
        props.setRetryBackoff(Duration.ofMillis(500));

        assertThat(props.getMaxRetries()).as("自定义重试次数").isEqualTo(5);
        assertThat(props.getTotalTimeout()).as("自定义总超时").isEqualTo(Duration.ofSeconds(120));
        assertThat(props.getRetryBackoff()).as("自定义退避").isEqualTo(Duration.ofMillis(500));
    }

    // ═══════════════════════════════════════════════════════
    // FeatureProperties
    // ═══════════════════════════════════════════════════════

    @Test
    void feature_subagent_should_be_enabled_by_default() {
        FeatureProperties props = new FeatureProperties();

        assertThat(props.getSubagent()).as("subagent 配置不应为空").isNotNull();
        assertThat(props.getSubagent().isEnabled()).as("subagent.enabled 默认 true").isTrue();
    }

    @Test
    void feature_subagent_should_accept_custom_value() {
        FeatureProperties props = new FeatureProperties();
        props.getSubagent().setEnabled(false);

        assertThat(props.getSubagent().isEnabled()).as("可关闭 subagent").isFalse();
    }

    @Test
    void feature_tool_routing_should_use_default_values() {
        FeatureProperties props = new FeatureProperties();

        assertThat(props.getToolRouting()).as("toolRouting 配置不应为空").isNotNull();
        assertThat(props.getToolRouting().isEnabled()).as("toolRouting.enabled 默认 true").isTrue();
        assertThat(props.getToolRouting().getMaxTagLength()).as("maxTagLength 默认 60").isEqualTo(60);
    }

    @Test
    void feature_tool_routing_should_accept_custom_values() {
        FeatureProperties props = new FeatureProperties();
        props.getToolRouting().setEnabled(false);
        props.getToolRouting().setMaxTagLength(40);

        assertThat(props.getToolRouting().isEnabled()).as("可关闭 toolRouting").isFalse();
        assertThat(props.getToolRouting().getMaxTagLength()).as("可自定义 maxTagLength").isEqualTo(40);
    }

    // ═══════════════════════════════════════════════════════
    // PromptGuardProperties
    // ═══════════════════════════════════════════════════════

    @Test
    void guard_should_have_empty_tool_lists_by_default() {
        PromptGuardProperties props = new PromptGuardProperties();

        assertThat(props.getBlockTools()).as("blockTools 默认空列表").isEmpty();
        assertThat(props.getConfirmTools()).as("confirmTools 默认空列表").isEmpty();
        assertThat(props.getBlockPatterns()).as("blockPatterns 默认空列表").isEmpty();
        assertThat(props.getConfirmPatterns()).as("confirmPatterns 默认空列表").isEmpty();
    }

    @Test
    void guard_pattern_rule_should_support_setter_getter() {
        PromptGuardProperties.PatternRule rule = new PromptGuardProperties.PatternRule();

        rule.setToolName(".*[Dd]elete.*");
        rule.setArguments(".*confirm.*");

        assertThat(rule.getToolName()).as("toolName 正则").isEqualTo(".*[Dd]elete.*");
        assertThat(rule.getArguments()).as("arguments 正则").isEqualTo(".*confirm.*");
    }

    @Test
    void guard_rate_limit_should_have_defaults() {
        PromptGuardProperties.RateLimit limit = new PromptGuardProperties.RateLimit();

        assertThat(limit.getMaxPerSession()).as("默认每会话 30 次").isEqualTo(30);
        assertThat(limit.getWindowSeconds()).as("默认窗口 60 秒").isEqualTo(60);
    }

}
