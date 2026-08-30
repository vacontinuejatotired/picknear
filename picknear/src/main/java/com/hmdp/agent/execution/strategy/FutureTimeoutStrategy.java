package com.hmdp.agent.execution.strategy;

import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * Future 超时策略（默认）
 *
 * <p>使用 CompletableFuture.orTimeout 实现超时控制。</p>
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "agent.subtask.dag.timeout.strategy", havingValue = "future", matchIfMissing = true)
public class FutureTimeoutStrategy implements TimeoutStrategy {

    @Resource
    @Qualifier("aiTaskExecutor")
    private Executor executor;

    @Override
    public <T> T executeWithTimeout(Supplier<T> task, long timeoutMs) throws Exception {
        try {
            return CompletableFuture.supplyAsync(() -> {
                try {
                    return task.get();
                } catch (Exception e) {
                    throw new CompletionException(e);
                }
            }, executor)
            .orTimeout(timeoutMs, TimeUnit.MILLISECONDS)
            .join();
        } catch (CompletionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof Exception) {
                throw (Exception) cause;
            }
            throw e;
        }
    }
}
