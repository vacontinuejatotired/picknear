package com.hmdp.agent.execution.strategy;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.function.Supplier;

/**
 * 无超时策略
 *
 * <p>直接执行任务，不设置超时。</p>
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "agent.subtask.dag.timeout.strategy", havingValue = "none")
public class NoTimeoutStrategy implements TimeoutStrategy {

    @Override
    public <T> T executeWithTimeout(Supplier<T> task, long timeoutMs) throws Exception {
        return task.get();
    }
}
