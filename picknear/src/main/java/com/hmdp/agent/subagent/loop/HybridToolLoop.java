package com.hmdp.agent.subagent.loop;

import com.hmdp.agent.config.SubTaskProperties;
import com.hmdp.agent.dag.executor.*;
import com.hmdp.agent.dag.plan.ExecutionPlan;
import com.hmdp.agent.dag.plan.PlanGenerator;
import com.hmdp.agent.dag.review.PlanReviewer;
import com.hmdp.agent.guard.GuardedToolCallback;
import com.hmdp.agent.model.ToolMetadata;
import com.hmdp.agent.task.model.SubTask;
import com.hmdp.agent.util.TextUtils;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
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
 * 混合 DAG 执行策略
 * 
 * <p>结合并行和串行的优点：</p>
 * <ul>
 *   <li>无依赖的工具：并行执行（提高效率）</li>
 *   <li>有依赖的工具：串行执行（保证正确性）</li>
 * </ul>
 * 
 * <p>配置：agent.subtask.tool-loop=hybrid</p>
 *
 * @author DAG Planning Executor
 * @version 1.9
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "agent.subtask.tool-loop", havingValue = "hybrid")
public class HybridToolLoop extends AbstractToolLoop {
    
    @Resource
    private PlanGenerator planGenerator;
    
    @Resource
    private PlanExecutor planExecutor;
    
    @Resource
    private ToolResultStore toolResultStore;
    
    @Resource
    private com.hmdp.agent.dag.strategy.ToolResultCompressor dagCompressor;
    
    @Resource
    private SubTaskProperties subTaskProperties;
    
    @Resource(required = false)
    private PlanReviewer planReviewer;
    
    @Resource
    private com.hmdp.agent.dag.plan.GraphAnalyzer graphAnalyzer;
    
    @Override
    public String toolCallRule() {
        return "根据依赖关系自动编排执行顺序，无依赖的工具并行执行";
    }
    
    @Override
    protected ToolResponseMessage executeRound(AssistantMessage out, SubAgentToolLoopContext ctx,
            Map<String, String> doneSummary, List<SubTask> remaining,
            AtomicInteger callCounter, AtomicInteger dupCounter, AtomicReference<String> lastCallKey) {
        
        // 1. 解析 LLM 返回的工具调用
        List<String> selectedTools = out.getToolCalls().stream()
            .map(AssistantMessage.ToolCall::name)
            .collect(Collectors.toList());
        
        // 2. 审查（可选）
        if (planReviewer != null) {
            PlanReviewer.ReviewResult review = planReviewer.review(selectedTools, ctx.plan().getUserInput());
            if (!review.isApproved()) {
                log.warn("规划审查未通过: {}", review.getReason());
                if (review.getSuggestedTools() != null) {
                    selectedTools = review.getSuggestedTools();
                }
            }
        }
        
        // 3. 生成执行计划
        ExecutionPlan plan = planGenerator.plan(selectedTools);
        
        if (!plan.isValid()) {
            log.warn("执行计划无效: {}，降级到串行执行", plan.getInvalidReason());
            return fallbackSerialExecution(out, ctx, doneSummary, remaining, callCounter, dupCounter, lastCallKey);
        }
        
        // 4. 构建工具调用器（包含参数注入逻辑）
        Map<String, ToolInvoker> tools = buildToolInvokers(out.getToolCalls(), ctx);
        
        // 5. 清空上次执行结果
        toolResultStore.clearAll();
        
        // 6. 执行 DAG
        DagExecutionResult result = planExecutor.execute(plan, tools);
        
        // 7. 转换为 ToolResponseMessage
        return buildToolResponse(out.getToolCalls(), result, doneSummary, remaining, callCounter);
    }
    
    /**
     * 降级到串行执行
     */
    private ToolResponseMessage fallbackSerialExecution(AssistantMessage out, SubAgentToolLoopContext ctx,
            Map<String, String> doneSummary, List<SubTask> remaining,
            AtomicInteger callCounter, AtomicInteger dupCounter, AtomicReference<String> lastCallKey) {
        log.warn("降级到串行执行");
        // 复用 SerialToolLoop 的逻辑
        // 这里简单处理，返回错误信息
        List<ToolResponse> responses = new ArrayList<>();
        for (AssistantMessage.ToolCall tc : out.getToolCalls()) {
            responses.add(new ToolResponse(tc.id(), tc.name(), "错误：执行计划无效，已降级"));
            callCounter.incrementAndGet();
            removeExecuted(remaining, tc.name());
        }
        return ToolResponseMessage.builder().responses(responses).build();
    }
    
    /**
     * 构建工具调用器映射
     * 
     * <p>参数注入流程：</p>
     * <ol>
     *   <li>LLM 返回的 tool_calls 包含 Agent 参数（JSON 格式）</li>
     *   <li>ToolCallback.call() 内部负责 JSON 反序列化为方法参数</li>
     *   <li>依赖工具的参数由 ToolResultStore.get() / getByType() 在工具内部获取</li>
     *   <li>工具方法内部通过参数类型自动推断获取依赖结果</li>
     * </ol>
     */
    private Map<String, ToolInvoker> buildToolInvokers(
            List<AssistantMessage.ToolCall> toolCalls, SubAgentToolLoopContext ctx) {
        
        Map<String, ToolInvoker> invokers = new HashMap<>();
        ToolContext toolCtx = new ToolContext(ctx.toolContext() == null ? Map.of() : ctx.toolContext());
        
        for (AssistantMessage.ToolCall tc : toolCalls) {
            ToolCallback cb = findByName(ctx.callbacks(), tc.name());
            if (cb != null) {
                ToolMetadata meta = graphAnalyzer.getMetadata(tc.name());
                invokers.put(tc.name(), new ToolInvoker() {
                    @Override
                    public Object invoke() throws Exception {
                        // 1. 解析 Agent 参数（从 JSON，由 Spring AI ToolCallback 处理）
                        // 2. 依赖参数由工具内部通过 ToolResultStore.get(index) 获取
                        // 3. 合并后执行工具方法
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
    
    /**
     * 构建工具响应
     */
    private ToolResponseMessage buildToolResponse(
            List<AssistantMessage.ToolCall> toolCalls,
            DagExecutionResult result,
            Map<String, String> doneSummary,
            List<SubTask> remaining,
            AtomicInteger callCounter) {
        
        int compressLength = subTaskProperties.getCompressLength();  // 从配置读取
        List<ToolResponse> responses = new ArrayList<>();
        
        for (AssistantMessage.ToolCall tc : toolCalls) {
            Object rawResult = result.getResults().get(tc.name());
            String compact;
            
            if (rawResult == null) {
                // 失败工具：提供错误信息
                String reason = result.getFailedReasons() != null 
                    ? result.getFailedReasons().get(tc.name()) 
                    : "工具执行失败";
                compact = "错误：" + reason;
            } else {
                // 成功工具：压缩结果
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
