package com.hmdp.agent.execution.loop;

import com.hmdp.agent.config.DagProperties;
import com.hmdp.agent.execution.metrics.ToolExecutionMetrics;
import com.hmdp.agent.execution.strategy.RetryStrategy;
import com.hmdp.agent.execution.strategy.TimeoutStrategy;
import com.hmdp.agent.plan.executionPlan.ExecutionPlan;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;
import java.util.stream.Collectors;

/**
 * 默认 DAG 执行器实现
 *
 * <p>按层并行执行工具，支持超时、重试、指标收集。</p>
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "agent.subtask.dag.executor", havingValue = "local", matchIfMissing = true)
public class DefaultPlanExecutor implements PlanExecutor {

    @Resource
    private ToolResultStore toolResultStore;

    @Resource
    @Qualifier("aiTaskExecutor")
    private Executor executor;

    @Resource
    private DagProperties dagProperties;

    @Resource
    private RetryStrategy retryStrategy;

    @Resource
    private TimeoutStrategy timeoutStrategy;

    @Override
    public DagExecutionResult execute(ExecutionPlan plan, Map<String, ToolInvoker> tools) {
        if (!plan.isValid()) {
            return DagExecutionResult.failed(plan.getInvalidReason());
        }

        long startTime = System.currentTimeMillis();
        Map<String, Object> results = new ConcurrentHashMap<>();
        Map<String, String> failedReasons = new ConcurrentHashMap<>();
        List<String> executedTools = new ArrayList<>();
        List<String> failedTools = new ArrayList<>();
        List<ToolExecutionMetrics> metrics = new CopyOnWriteArrayList<>();

        try {
            for (int i = 0; i < plan.getLayers().size(); i++) {
                final int layerIndex = i;
                List<String> layer = plan.getLayers().get(i);
                log.info("执行 Layer {}: {}", i, layer);

                List<ToolResultEntry> layerEntries = new CopyOnWriteArrayList<>();

                toolResultStore.setCurrentLayerEntries(layerEntries);

                List<CompletableFuture<Void>> futures = layer.stream()
                    .map(toolName -> CompletableFuture.runAsync(() -> {
                        long toolStartTime = System.currentTimeMillis();

                        try {
                            ToolInvoker invoker = tools.get(toolName);
                            Object result = executeWithRetryAndTimeout(toolName, invoker);

                            results.put(toolName, result);
                            toolResultStore.store(toolName, result, invoker.getReturnType());

                            layerEntries.add(ToolResultEntry.builder()
                                .toolName(toolName)
                                .result(result)
                                .type(invoker.getReturnType())
                                .build());

                            executedTools.add(toolName);
                            long duration = System.currentTimeMillis() - toolStartTime;
                            log.debug("工具执行完成: {} ({}ms)", toolName, duration);

                            metrics.add(ToolExecutionMetrics.builder()
                                .toolName(toolName)
                                .duration(duration)
                                .success(true)
                                .layer(layerIndex)
                                .executedAt(LocalDateTime.now())
                                .build());

                        } catch (Exception e) {
                            log.error("工具执行失败: {}", toolName, e);
                            failedTools.add(toolName);
                            failedReasons.put(toolName, e.getMessage());

                            ToolInvoker invoker = tools.get(toolName);
                            results.put(toolName, null);
                            toolResultStore.store(toolName, null, invoker.getReturnType());
                            layerEntries.add(ToolResultEntry.builder()
                                .toolName(toolName)
                                .result(null)
                                .type(invoker.getReturnType())
                                .build());

                            long duration = System.currentTimeMillis() - toolStartTime;
                            metrics.add(ToolExecutionMetrics.builder()
                                .toolName(toolName)
                                .duration(duration)
                                .success(false)
                                .errorMessage(e.getMessage())
                                .layer(layerIndex)
                                .executedAt(LocalDateTime.now())
                                .build());
                        }
                    }, executor))
                    .collect(Collectors.toList());

                try {
                    CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                        .orTimeout(dagProperties.getLayerTimeoutSeconds(), TimeUnit.SECONDS)
                        .join();
                } catch (CompletionException e) {
                    if (e.getCause() instanceof TimeoutException) {
                        log.error("Layer {} 执行超时 ({}s)，取消未完成任务",
                            i, dagProperties.getLayerTimeoutSeconds());
                        futures.forEach(f -> f.cancel(true));

                        return DagExecutionResult.builder()
                            .success(false)
                            .results(results)
                            .failedReasons(failedReasons)
                            .executedTools(executedTools)
                            .failedTools(failedTools)
                            .metrics(metrics)
                            .duration(System.currentTimeMillis() - startTime)
                            .errorMessage("Layer " + i + " 执行超时")
                            .build();
                    } else {
                        log.error("Layer {} 执行异常", i, e);
                    }
                }

                toolResultStore.clearCurrentLayer();

                log.info("Layer {} 完成", i);
            }

            long duration = System.currentTimeMillis() - startTime;
            boolean success = failedTools.isEmpty();

            return DagExecutionResult.builder()
                .success(success)
                .results(results)
                .failedReasons(failedReasons)
                .executedTools(executedTools)
                .failedTools(failedTools)
                .metrics(metrics)
                .duration(duration)
                .build();

        } catch (Exception e) {
            log.error("DAG 执行异常", e);
            long duration = System.currentTimeMillis() - startTime;
            return DagExecutionResult.builder()
                .success(false)
                .results(results)
                .failedReasons(failedReasons)
                .executedTools(executedTools)
                .failedTools(failedTools)
                .metrics(metrics)
                .duration(duration)
                .errorMessage("执行异常: " + e.getMessage())
                .build();
        }
    }

    private Object executeWithRetryAndTimeout(String toolName, ToolInvoker invoker)
            throws Exception {
        long timeoutMs = dagProperties.getToolTimeout(toolName) * 1000L;
        int maxRetries = dagProperties.getRetry().getMaxRetries();

        Exception lastException = null;

        for (int attempt = 0; attempt <= maxRetries; attempt++) {
            try {
                return timeoutStrategy.executeWithTimeout(() -> {
                    try {
                        return invoker.invoke();
                    } catch (Exception e) {
                        throw new CompletionException(e);
                    }
                }, timeoutMs);
            } catch (Exception e) {
                lastException = e;
                if (retryStrategy.shouldRetry(e, attempt)) {
                    long delay = retryStrategy.getRetryDelay(attempt);
                    log.warn("工具 {} 第 {} 次重试，等待 {}ms: {}",
                        toolName, attempt + 1, delay, e.getMessage());
                    Thread.sleep(delay);
                }
            }
        }

        throw lastException;
    }
}
