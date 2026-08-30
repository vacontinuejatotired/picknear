package com.hmdp.agent.execution.loop.strategy;

import com.hmdp.agent.config.SubTaskProperties;
import com.hmdp.agent.execution.loop.DagExecutionResult;
import com.hmdp.agent.execution.loop.PlanExecutor;
import com.hmdp.agent.execution.loop.ToolInvoker;
import com.hmdp.agent.execution.loop.ToolResultStore;
import com.hmdp.agent.plan.executionPlan.ExecutionPlan;
import com.hmdp.agent.plan.executionPlan.PlanGenerator;
import com.hmdp.agent.plan.review.PlanReviewer;
import com.hmdp.agent.plan.executionPlan.model.ToolMetadata;
import com.hmdp.agent.subagent.loop.AbstractToolLoop;
import com.hmdp.agent.subagent.loop.ToolLoopContext;
import com.hmdp.agent.plan.model.SubTask;
import com.hmdp.agent.util.TextUtils;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage.ToolResponse;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

/**
 * DAG 混合执行策略。
 * <p>
 * 结合并行和串行的优点：
 * <ul>
 *   <li>无依赖的工具：并行执行（提高效率）</li>
 *   <li>有依赖的工具：串行执行（保证正确性）</li>
 * </ul>
 * 适用场景：工具之间存在明确的依赖关系。
 * </p>
 *
 * @see com.hmdp.agent.subagent.loop.ToolExecutionStrategy
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "agent.subtask.tool-loop", havingValue = "hybrid")
public class DagStrategy extends AbstractToolLoop {

    @Resource
    private PlanGenerator planGenerator;

    @Resource
    private PlanExecutor planExecutor;

    @Resource
    private ToolResultStore toolResultStore;

    @Resource
    private com.hmdp.agent.execution.strategy.ToolResultCompressor dagCompressor;

    @Resource
    private SubTaskProperties subTaskProperties;

    @Resource
    private ObjectProvider<PlanReviewer> planReviewerProvider;

    @Resource
    private com.hmdp.agent.plan.executionPlan.GraphAnalyzer graphAnalyzer;

    @Override
    public String toolCallRule() {
        return "根据依赖关系自动编排执行顺序，无依赖的工具并行执行";
    }

    @Override
    protected ToolResponseMessage executeRound(AssistantMessage out, ToolLoopContext ctx,
            Map<String, String> doneSummary, List<SubTask> remaining,
            AtomicInteger callCounter, AtomicInteger dupCounter, AtomicReference<String> lastCallKey) {

        List<String> selectedTools = out.getToolCalls().stream()
            .map(AssistantMessage.ToolCall::name)
            .collect(Collectors.toList());

        PlanReviewer planReviewer = planReviewerProvider.getIfAvailable();
        if (planReviewer != null) {
            PlanReviewer.ReviewResult review = planReviewer.review(selectedTools, ctx.plan().getUserInput());
            if (!review.isApproved()) {
                log.warn("规划审查未通过: {}", review.getReason());
                if (review.getSuggestedTools() != null) {
                    selectedTools = review.getSuggestedTools();
                }
            }
        }

        ExecutionPlan plan = planGenerator.plan(selectedTools);

        if (!plan.isValid()) {
            log.warn("执行计划无效: {}，降级到串行执行", plan.getInvalidReason());
            return fallbackSerialExecution(out, doneSummary, remaining, callCounter);
        }

        Map<String, ToolInvoker> tools = buildToolInvokers(out.getToolCalls(), ctx);

        toolResultStore.clearAll();

        DagExecutionResult result = planExecutor.execute(plan, tools);

        return buildToolResponse(out.getToolCalls(), result, doneSummary, remaining, callCounter);
    }

    private ToolResponseMessage fallbackSerialExecution(AssistantMessage out,
            Map<String, String> doneSummary, List<SubTask> remaining, AtomicInteger callCounter) {
        log.warn("降级到串行执行");
        List<ToolResponse> responses = new ArrayList<>();
        for (AssistantMessage.ToolCall tc : out.getToolCalls()) {
            responses.add(new ToolResponse(tc.id(), tc.name(), "错误：执行计划无效，已降级"));
            callCounter.incrementAndGet();
            removeExecuted(remaining, tc.name());
        }
        return ToolResponseMessage.builder().responses(responses).build();
    }

    private Map<String, ToolInvoker> buildToolInvokers(
            List<AssistantMessage.ToolCall> toolCalls, ToolLoopContext ctx) {

        Map<String, ToolInvoker> invokers = new HashMap<>();
        ToolContext toolCtx = new ToolContext(ctx.toolContext() == null ? Map.of() : ctx.toolContext());

        for (AssistantMessage.ToolCall tc : toolCalls) {
            ToolCallback cb = findByName(ctx.callbacks(), tc.name());
            if (cb != null) {
                ToolMetadata meta = graphAnalyzer.getMetadata(tc.name());
                invokers.put(tc.name(), new ToolInvoker() {
                    @Override
                    public Object invoke() throws Exception {
                        return cb.call(tc.arguments(), toolCtx);
                    }

                    @Override
                    public Class<?> getReturnType() {
                        return meta != null ? meta.getReturnType() : Object.class;
                    }
                });
            }
        }

        return invokers;
    }

    private ToolResponseMessage buildToolResponse(
            List<AssistantMessage.ToolCall> toolCalls,
            DagExecutionResult result,
            Map<String, String> doneSummary,
            List<SubTask> remaining,
            AtomicInteger callCounter) {

        int compressLength = subTaskProperties.getCompressLength();
        List<ToolResponse> responses = new ArrayList<>();

        for (AssistantMessage.ToolCall tc : toolCalls) {
            Object rawResult = result.getResults().get(tc.name());
            String compact;

            if (rawResult == null) {
                String reason = result.getFailedReasons() != null
                    ? result.getFailedReasons().get(tc.name())
                    : "工具执行失败";
                compact = "错误：" + reason;
            } else {
                String rawStr = rawResult.toString();
                compact = dagCompressor.compress(rawStr, tc.name(), compressLength);
            }

            responses.add(new ToolResponse(tc.id(), tc.name(), compact));
            doneSummary.put(tc.name(), TextUtils.truncate(compact, 50));
            callCounter.incrementAndGet();
            removeExecuted(remaining, tc.name());
        }

        return ToolResponseMessage.builder().responses(responses).build();
    }
}
