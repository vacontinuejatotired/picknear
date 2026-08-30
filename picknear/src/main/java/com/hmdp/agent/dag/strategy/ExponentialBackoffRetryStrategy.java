package com.hmdp.agent.dag.strategy;

import com.hmdp.agent.config.DagProperties;
import com.hmdp.agent.model.ToolMetadata;
import com.hmdp.agent.plan.executionPlan.GraphAnalyzer;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * 指数退避重试策略（默认）
 *
 * <p>重试延迟按指数增长：baseDelay * 2^attempt</p>
 * <p>例如：1s → 2s → 4s → ...</p>
 *
 * <p>重试判断逻辑：</p>
 * <ol>
 *   <li>工具级 {@code @ToolMeta(idempotent, maxRetries, retryOnTimeout)} 优先</li>
 *   <li>全局配置兜底</li>
 * </ol>
 *
 * @author DAG Planning Executor
 * @version 2.0
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "agent.subtask.dag.retry.strategy", havingValue = "exponential", matchIfMissing = true)
public class ExponentialBackoffRetryStrategy implements RetryStrategy {

    @Resource
    private DagProperties dagProperties;

    @Resource
    private GraphAnalyzer graphAnalyzer;

    @Override
    public boolean shouldRetry(Exception e, int attempt) {
        // 无工具上下文时，使用全局配置
        return shouldRetry(e, attempt, null, false);
    }

    @Override
    public boolean shouldRetry(Exception e, int attempt, String toolName, boolean isTimeout) {
        // 1. 查询工具元数据
        ToolMetadata meta = toolName != null ? graphAnalyzer.getMetadata(toolName) : null;

        // 2. 检查全局开关
        if (!dagProperties.getRetry().isEnabled()) {
            log.debug("全局重试已禁用，跳过重试");
            return false;
        }

        // 3. 基于工具元数据判断
        if (meta != null) {
            boolean retryable = meta.isRetryable(isTimeout);
            int maxRetries = meta.getEffectiveMaxRetries(dagProperties.getDefaultMaxRetries());

            if (!retryable) {
                log.debug("工具 {} 声明为不可重试（idempotent={}, retryOnTimeout={}），跳过重试",
                    toolName, meta.isIdempotent(), meta.getRetryOnTimeout());
                return false;
            }

            if (attempt >= maxRetries) {
                log.debug("工具 {} 已达最大重试次数 {}（attempt={}），跳过重试",
                    toolName, maxRetries, attempt);
                return false;
            }

            // 检查异常类型是否可重试（兜底配置）
            return isRetryableException(e);
        }

        // 4. 无元数据时，使用全局配置
        int maxRetries = dagProperties.getDefaultMaxRetries();
        if (attempt >= maxRetries) {
            return false;
        }

        return isRetryableException(e);
    }

    /**
     * 检查异常类型是否在可重试列表中（兜底配置）
     */
    private boolean isRetryableException(Exception e) {
        var retryableErrors = dagProperties.getRetry().getRetryableErrors();
        if (retryableErrors.isEmpty()) {
            // 兜底配置为空时，允许重试（由工具元数据控制）
            return true;
        }

        String exceptionName = e.getClass().getName();
        String simpleName = e.getClass().getSimpleName();

        return retryableErrors.stream()
            .anyMatch(r -> exceptionName.contains(r) || simpleName.equals(r));
    }

    @Override
    public long getRetryDelay(int attempt) {
        return dagProperties.getRetry().getBaseDelayMs() * (1L << attempt);
    }
}
