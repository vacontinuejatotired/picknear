package com.hmdp.agent.dag.strategy;

import com.hmdp.agent.config.DagProperties;
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
 * @author DAG Planning Executor
 * @version 1.9
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "agent.subtask.dag.retry.strategy", havingValue = "exponential", matchIfMissing = true)
public class ExponentialBackoffRetryStrategy implements RetryStrategy {
    
    @Resource
    private DagProperties dagProperties;
    
    @Override
    public boolean shouldRetry(Exception e, int attempt) {
        DagProperties.RetryProperties retry = dagProperties.getRetry();
        
        if (attempt >= retry.getMaxRetries()) {
            return false;
        }
        
        String exceptionName = e.getClass().getName();
        String simpleName = e.getClass().getSimpleName();
        
        return retry.getRetryableErrors().stream()
            .anyMatch(r -> exceptionName.equals(r) || simpleName.equals(r));
    }
    
    @Override
    public long getRetryDelay(int attempt) {
        return dagProperties.getRetry().getBaseDelayMs() * (1L << attempt);
    }
}
