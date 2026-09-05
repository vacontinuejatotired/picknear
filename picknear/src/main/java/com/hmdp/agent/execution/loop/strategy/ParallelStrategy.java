package com.hmdp.agent.execution.loop.strategy;

import com.hmdp.agent.config.SubTaskProperties;
import com.hmdp.agent.guard.model.ConfirmRequiredException;
import com.hmdp.agent.subagent.loop.AbstractToolLoop;
import com.hmdp.agent.subagent.loop.ToolLoopContext;
import com.hmdp.agent.plan.model.SubTask;
import com.hmdp.agent.util.TextUtils;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage.ToolResponse;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

/**
 * 并行策略：一轮可执行多个独立工具调用。
 * <p>
 * prompt 规则允许"独立且无依赖的工具一次同时调用多个"。
 * 适用场景：多个独立查询（如同时查天气和博客）。
 * </p>
 *
 * @see com.hmdp.agent.subagent.loop.ToolExecutionStrategy
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "agent.subtask.tool-loop", havingValue = "batch")
public class ParallelStrategy extends AbstractToolLoop {

    @Resource(name = "aiTaskExecutor")
    private Executor executor;

    @Resource
    private ObservationRegistry observationRegistry;

    @Override
    public String toolCallRule() {
        return "独立且无依赖的工具可以一次同时调用多个；依赖上一个结果的任务仍需等待其返回";
    }

    @Override
    protected ToolResponseMessage executeRound(AssistantMessage out, ToolLoopContext ctx,
            Map<String, String> doneSummary, List<SubTask> remaining,
            AtomicInteger callCounter, AtomicInteger dupCounter, AtomicReference<String> lastCallKey) {
        SubTaskProperties props = ctx.props();
        List<AssistantMessage.ToolCall> calls = out.getToolCalls();
        int n = calls.size();

        ConcurrentHashMap<Integer, Step1> step1 = new ConcurrentHashMap<>();
        runConcurrent(IntStream.range(0, n).boxed().collect(Collectors.toList()),
                props.isParallelTools(), i -> step1.put(i, callPhase(calls.get(i), ctx)));
        for (int i = 0; i < n; i++) {
            if (step1.get(i).confirm() != null) {
                throw step1.get(i).confirm();
            }
        }

        ConcurrentHashMap<Integer, String> compacts = new ConcurrentHashMap<>();
        runConcurrent(IntStream.range(0, n).boxed().collect(Collectors.toList()),
                props.isParallelCompress(), i -> {
                    Step1 st = step1.get(i);
                    if (st.error() != null) {
                        return;
                    }
                    compacts.put(i, compressor.compress(st.raw(), calls.get(i).name(), props.getCompressLength()));
                });

        List<ToolResponse> responses = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            AssistantMessage.ToolCall tc = calls.get(i);
            Step1 st = step1.get(i);
            ToolResponse response;
            String doneValue;
            if (st.error() != null) {
                String msg = "错误：" + st.error().getMessage();
                response = new ToolResponse(tc.id(), tc.name(), msg);
                doneValue = msg;
            } else {
                String compact = compacts.get(i);
                response = new ToolResponse(tc.id(), tc.name(), compact);
                doneValue = TextUtils.truncate(compact, 50);
            }
            String key = tc.name() + "|" + (tc.arguments() == null ? "" : tc.arguments());
            if (key.equals(lastCallKey.get())) {
                dupCounter.incrementAndGet();
                log.warn("[ToolLoop] 检测到同工具同参数连续重复调用 [tool={}]，不抑制（数据可能已变更）", tc.name());
            } else {
                lastCallKey.set(key);
            }
            responses.add(response);
            doneSummary.put(tc.name(), doneValue);
            callCounter.incrementAndGet();
            removeExecuted(remaining, tc.name());
        }
        return ToolResponseMessage.builder().responses(responses).build();
    }

    private record Step1(String raw, Exception error, ConfirmRequiredException confirm) {}

    private Step1 callPhase(AssistantMessage.ToolCall tc, ToolLoopContext ctx) {
        ToolCallback cb = findByName(ctx.callbacks(), tc.name());
        if (cb == null) {
            return new Step1(null, new RuntimeException("工具不可用"), null);
        }
        try {
            String raw = invokeToolAndRecord(tc.name(), () -> cb.call(tc.arguments(),
                    new ToolContext(ctx.toolContext() == null ? Map.of() : ctx.toolContext())), ctx);
            return new Step1(raw, null, null);
        } catch (ConfirmRequiredException e) {
            return new Step1(null, null, e);
        } catch (Exception e) {
            log.warn("[ToolLoop] 工具执行失败 [tool={}, err={}]", tc.name(), e.getMessage());
            return new Step1(null, e, null);
        }
    }

    private void runConcurrent(List<Integer> indexes, boolean parallel, Consumer<Integer> task) {
        if (!parallel || indexes.size() <= 1) {
            indexes.forEach(task);
            return;
        }
        Observation observation = observationRegistry != null
                ? observationRegistry.getCurrentObservation() : null;
        CompletableFuture<?>[] futures = indexes.stream()
                .map(i -> CompletableFuture.runAsync(() -> {
                    try (Observation.Scope scope = observation != null ? observation.openScope() : null) {
                        task.accept(i);
                    }
                }, executor))
                .toArray(CompletableFuture[]::new);
        CompletableFuture.allOf(futures).join();
    }
}
