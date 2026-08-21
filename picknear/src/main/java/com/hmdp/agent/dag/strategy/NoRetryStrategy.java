package com.hmdp.agent.dag.strategy;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * 无重试策略
 * 
 * <p>工具执行失败时直接抛出异常，不进行重试。</p>
 *
 * @author DAG Planning Executor
 * @version 1.9
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "agent.subtask.dag.retry.strategy", havingValue = "none")
public class NoRetryStrategy implements RetryStrategy {
    
    @Override
    public boolean shouldRetry(Exception e, int attempt) {
        log.debug("NoRetryStrategy: 工具执行失败，不重试");
        return false;
    }
    
    @Override
    public long getRetryDelay(int attempt) {
        return 0;
    }
}
