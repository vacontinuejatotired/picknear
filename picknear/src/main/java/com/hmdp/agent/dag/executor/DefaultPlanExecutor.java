package com.hmdp.agent.dag.executor;

import com.hmdp.agent.config.DagProperties;
import com.hmdp.agent.dag.metrics.ToolExecutionMetrics;
import com.hmdp.agent.dag.plan.ExecutionPlan;
import com.hmdp.agent.dag.strategy.RetryStrategy;
import com.hmdp.agent.dag.strategy.TimeoutStrategy;
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
 *
 * @author DAG Planning Executor
 * @version 1.9
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
                
                // 使用 ToolResultEntry 包裹结果和类型
                List<ToolResultEntry> layerEntries = new CopyOnWriteArrayList<>();
                
                // 设置当前层上下文
                toolResultStore.setCurrentLayerEntries(layerEntries);
                
                // 并行执行当前层
                List<CompletableFuture<Void>> futures = layer.stream()
                    .map(toolName -> CompletableFuture.runAsync(() -> {
                        long toolStartTime = System.currentTimeMillis();
                        
                        try {
                            // 执行工具（带重试和超时）
                            ToolInvoker invoker = tools.get(toolName);
                            Object result = executeWithRetryAndTimeout(toolName, invoker);
                            
                            // 存储结果（带类型信息）
                            results.put(toolName, result);
                            toolResultStore.store(toolName, result, invoker.getReturnType());
                            
                            // 记录到当前层（使用 ToolResultEntry）
                            layerEntries.add(ToolResultEntry.builder()
                                .toolName(toolName)
                                .result(result)
                                .type(invoker.getReturnType())
                                .build());
                            
                            executedTools.add(toolName);
                            long duration = System.currentTimeMillis() - toolStartTime;
                            log.debug("工具执行完成: {} ({}ms)", toolName, duration);
                            
                            // 记录指标（本次执行独立收集）
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
                            
                            // 失败时返回 null，保持类型一致（使用 ToolResultEntry）
                            ToolInvoker invoker = tools.get(toolName);
                            results.put(toolName, null);
                            toolResultStore.store(toolName, null, invoker.getReturnType());  // 失败也要存储类型信息
                            layerEntries.add(ToolResultEntry.builder()
                                .toolName(toolName)
                                .result(null)
                                .type(invoker.getReturnType())
                                .build());
                            
                            // 记录失败指标
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
                
                // 等待当前层完成（带超时）
                try {
                    CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                        .orTimeout(dagProperties.getLayerTimeoutSeconds(), TimeUnit.SECONDS)
                        .join();
                } catch (CompletionException e) {
                    if (e.getCause() instanceof TimeoutException) {
                        log.error("Layer {} 执行超时 ({}s)，取消未完成任务",
                            i, dagProperties.getLayerTimeoutSeconds());
                        // 取消所有未完成的 futures
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
                
                // 清空当前层上下文
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
    
    /**
     * 执行工具（带重试和超时）
     * <p>
     * 重试循环在外层，Thread.sleep 不会阻塞线程池线程。
     * 重试判断基于工具元数据（{@code @ToolMeta}）和全局配置。
     * </p>
     *
     * @param toolName 工具名称
     * @param invoker  工具调用器
     * @return 工具执行结果
     * @throws Exception 执行失败且不可重试时抛出
     */
    private Object executeWithRetryAndTimeout(String toolName, ToolInvoker invoker)
            throws Exception {
        long timeoutMs = dagProperties.getToolTimeout(toolName) * 1000L;

        Exception lastException = null;
        boolean lastWasTimeout = false;

        // 使用工具级配置的最大重试次数（兜底用全局配置）
        int maxRetries = dagProperties.getDefaultMaxRetries();

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
                lastException = unwrap(e);
                lastWasTimeout = isTimeoutException(lastException);

                // 使用新的 shouldRetry 方法，传入工具名和超时标记
                if (retryStrategy.shouldRetry(lastException, attempt, toolName, lastWasTimeout)) {
                    long delay = retryStrategy.getRetryDelay(attempt);
                    log.warn("工具 {} 第 {} 次重试，等待 {}ms: {}",
                        toolName, attempt + 1, delay, lastException.getMessage());
                    Thread.sleep(delay);  // 外层 sleep，不影响其他工具
                } else {
                    log.debug("工具 {} 不可重试（attempt={}, timeout={}），抛出异常",
                        toolName, attempt, lastWasTimeout);
                    break;
                }
            }
        }

        throw lastException;
    }

    /**
     * 解包异常（移除 CompletionException 包装）
     */
    private Exception unwrap(Exception e) {
        if (e instanceof CompletionException && e.getCause() instanceof Exception cause) {
            return cause;
        }
        return e;
    }

    /**
     * 判断是否为超时异常
     */
    private boolean isTimeoutException(Exception e) {
        String name = e.getClass().getSimpleName();
        return name.contains("Timeout") || name.contains("timeout");
    }
}
