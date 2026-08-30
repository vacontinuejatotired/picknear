package com.hmdp.agent.dag.strategy;

import com.hmdp.agent.config.DagProperties;
import com.hmdp.agent.model.ToolMetadata;
import com.hmdp.agent.plan.executionPlan.GraphAnalyzer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.net.SocketTimeoutException;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/**
 * ExponentialBackoffRetryStrategy 重试策略测试。
 * <p>
 * 覆盖基于工具元数据的重试判断逻辑。
 */
@ExtendWith(MockitoExtension.class)
class ExponentialBackoffRetryStrategyTest {

    @Mock
    private DagProperties dagProperties;

    @Mock
    private GraphAnalyzer graphAnalyzer;

    @InjectMocks
    private ExponentialBackoffRetryStrategy retryStrategy;

    private DagProperties.RetryProperties retryProps;

    @BeforeEach
    void setUp() {
        retryProps = new DagProperties.RetryProperties();
        retryProps.setEnabled(true);
        retryProps.setMaxRetries(3);
        retryProps.setRetryableErrors(List.of("SocketTimeoutException"));

        when(dagProperties.getRetry()).thenReturn(retryProps);
        when(dagProperties.getDefaultMaxRetries()).thenReturn(3);
    }

    // ═══════════════════════════════════════════════════════
    // 全局开关测试
    // ═══════════════════════════════════════════════════════

    @Test
    void should_not_retry_when_global_switch_disabled() {
        retryProps.setEnabled(false);

        boolean result = retryStrategy.shouldRetry(
            new RuntimeException("test"), 0, "queryWeather", false);

        assertThat(result).as("全局开关关闭时不可重试").isFalse();
    }

    // ═══════════════════════════════════════════════════════
    // 工具级配置测试
    // ═══════════════════════════════════════════════════════

    @Test
    void should_not_retry_when_tool_not_idempotent() {
        ToolMetadata meta = ToolMetadata.builder()
            .name("deductBalance")
            .idempotent(false)
            .build();
        when(graphAnalyzer.getMetadata("deductBalance")).thenReturn(meta);

        boolean result = retryStrategy.shouldRetry(
            new RuntimeException("test"), 0, "deductBalance", false);

        assertThat(result).as("非幂等工具不可重试").isFalse();
    }

    @Test
    void should_retry_when_tool_idempotent() {
        ToolMetadata meta = ToolMetadata.builder()
            .name("queryWeather")
            .idempotent(true)
            .build();
        when(graphAnalyzer.getMetadata("queryWeather")).thenReturn(meta);

        boolean result = retryStrategy.shouldRetry(
            new RuntimeException("test"), 0, "queryWeather", false);

        assertThat(result).as("幂等工具可重试").isTrue();
    }

    @Test
    void should_not_retry_when_tool_max_retries_zero() {
        ToolMetadata meta = ToolMetadata.builder()
            .name("cancelOrder")
            .idempotent(true)
            .maxRetries(0)
            .build();
        when(graphAnalyzer.getMetadata("cancelOrder")).thenReturn(meta);

        boolean result = retryStrategy.shouldRetry(
            new RuntimeException("test"), 0, "cancelOrder", false);

        assertThat(result).as("maxRetries=0 禁止重试").isFalse();
    }

    @Test
    void should_not_retry_when_exceed_tool_max_retries() {
        ToolMetadata meta = ToolMetadata.builder()
            .name("queryWeather")
            .idempotent(true)
            .maxRetries(2)
            .build();
        when(graphAnalyzer.getMetadata("queryWeather")).thenReturn(meta);

        boolean result = retryStrategy.shouldRetry(
            new RuntimeException("test"), 2, "queryWeather", false);

        assertThat(result).as("超过工具级重试次数").isFalse();
    }

    @Test
    void should_retry_within_tool_max_retries() {
        ToolMetadata meta = ToolMetadata.builder()
            .name("queryWeather")
            .idempotent(true)
            .maxRetries(2)
            .build();
        when(graphAnalyzer.getMetadata("queryWeather")).thenReturn(meta);

        boolean result = retryStrategy.shouldRetry(
            new RuntimeException("test"), 1, "queryWeather", false);

        assertThat(result).as("未超过工具级重试次数").isTrue();
    }

    // ═══════════════════════════════════════════════════════
    // 超时场景测试
    // ═══════════════════════════════════════════════════════

    @Test
    void should_not_retry_timeout_when_retry_on_timeout_disabled() {
        ToolMetadata meta = ToolMetadata.builder()
            .name("deductBalance")
            .idempotent(false)
            .retryOnTimeout(0)
            .build();
        when(graphAnalyzer.getMetadata("deductBalance")).thenReturn(meta);

        boolean result = retryStrategy.shouldRetry(
            new SocketTimeoutException("timeout"), 0, "deductBalance", true);

        assertThat(result).as("retryOnTimeout=0 超时不可重试").isFalse();
    }

    @Test
    void should_retry_timeout_when_retry_on_timeout_enabled() {
        ToolMetadata meta = ToolMetadata.builder()
            .name("writeData")
            .idempotent(false)
            .retryOnTimeout(1)
            .build();
        when(graphAnalyzer.getMetadata("writeData")).thenReturn(meta);

        boolean result = retryStrategy.shouldRetry(
            new SocketTimeoutException("timeout"), 0, "writeData", true);

        assertThat(result).as("retryOnTimeout=1 超时可重试").isTrue();
    }

    @Test
    void should_follow_idempotent_when_retry_on_timeout_default() {
        ToolMetadata meta = ToolMetadata.builder()
            .name("queryWeather")
            .idempotent(true)
            .retryOnTimeout(-1)
            .build();
        when(graphAnalyzer.getMetadata("queryWeather")).thenReturn(meta);

        boolean result = retryStrategy.shouldRetry(
            new SocketTimeoutException("timeout"), 0, "queryWeather", true);

        assertThat(result).as("retryOnTimeout=-1 跟随幂等性").isTrue();
    }

    // ═══════════════════════════════════════════════════════
    // 兜底配置测试
    // ═══════════════════════════════════════════════════════

    @Test
    void should_fallback_to_global_when_no_metadata() {
        when(graphAnalyzer.getMetadata("unknownTool")).thenReturn(null);

        boolean result = retryStrategy.shouldRetry(
            new RuntimeException("test"), 0, "unknownTool", false);

        assertThat(result).as("无元数据时使用全局配置").isTrue();
    }

    @Test
    void should_not_retry_when_exceed_global_max_retries() {
        when(graphAnalyzer.getMetadata("unknownTool")).thenReturn(null);

        boolean result = retryStrategy.shouldRetry(
            new RuntimeException("test"), 3, "unknownTool", false);

        assertThat(result).as("超过全局重试次数").isFalse();
    }

    // ═══════════════════════════════════════════════════════
    // getRetryDelay() 测试
    // ═══════════════════════════════════════════════════════

    @Test
    void retry_delay_should_use_exponential_backoff() {
        assertThat(retryStrategy.getRetryDelay(0)).as("第 0 次延迟 1000ms").isEqualTo(1000);
        assertThat(retryStrategy.getRetryDelay(1)).as("第 1 次延迟 2000ms").isEqualTo(2000);
        assertThat(retryStrategy.getRetryDelay(2)).as("第 2 次延迟 4000ms").isEqualTo(4000);
    }
}
