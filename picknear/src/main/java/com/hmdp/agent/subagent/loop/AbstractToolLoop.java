package com.hmdp.agent.subagent.loop;

import com.hmdp.agent.config.ChatModelObservationConventionConfig;
import com.hmdp.agent.config.SubTaskProperties;
import com.hmdp.agent.guard.GuardedToolCallback;
import com.hmdp.agent.prompt.PromptKeys;
import com.hmdp.agent.subagent.ToolResultCompressor;
import com.hmdp.agent.subagent.model.SubTaskPlan;
import com.hmdp.agent.subagent.prompt.SubAgentPromptBuilder;
import com.hmdp.agent.task.model.SubTask;
import com.hmdp.agent.util.TextUtils;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage.ToolResponse;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.tool.ToolCallback;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

/**
 * 工具循环抽象基类（模板方法）：承载共享循环骨架与支撑方法。
 * <p>
 * 循环骨架（与原有 {@code ToolCallLoopExecutor.execute} 一致）：
 * {@code for round: callModel → 无工具调用返回文本 → executeRound(钩子) → 按剩余任务重渲染 → 预算检查 → 触顶强制总结}。
 * 各策略只需实现 {@link #executeRound}（本轮工具怎么执行：串行 vs 并发）与 {@link #toolCallRule()}。
 * </p>
 */
@Slf4j
public abstract class AbstractToolLoop implements SubAgentToolLoop {

    @Resource
    protected ChatModel chatModel;

    @Resource
    protected ToolResultCompressor compressor;

    @Override
    public String execute(SubAgentToolLoopContext ctx) {
        SubTaskProperties props = ctx.props();
        int rounds = Math.max(1, props.getMaxToolRounds());
        List<SubTask> remaining = new ArrayList<>(ctx.plan().getTasks());
        // 已完成工具摘要：先带跨轮历史，再逐轮追加本轮压缩结果
        Map<String, String> doneSummary = new LinkedHashMap<>(
                ctx.plan().getHistorySummary() == null ? Map.of() : ctx.plan().getHistorySummary());
        List<Message> history = new ArrayList<>();
        history.add(new SystemMessage(ctx.systemText()));
        history.add(new UserMessage(ctx.initialPrompt()));

        // 重复/预算观测（不抑制调用，只记录 + 预算硬顶）
        AtomicInteger callCounter = new AtomicInteger(0);
        AtomicInteger dupCounter = new AtomicInteger(0);
        AtomicReference<String> lastCallKey = new AtomicReference<>();

        for (int round = 0; round < rounds; round++) {
            ChatResponse resp = callModel(history, ctx.callbacks(), ctx.toolContext(), buildTaskLabel(remaining));
            AssistantMessage out = resultOutput(resp);
            if (out == null) {
                return null;
            }
            if (!out.hasToolCalls()) {
                return out.getText();
            }
            history.add(assistantWithToolCalls(out));
            history.add(executeRound(out, ctx, doneSummary, remaining, callCounter, dupCounter, lastCallKey));
            // 每轮用更新后的计划重渲染 user 消息：历史摘要反映已完成工具、任务列表缩到剩余
            if (!doneSummary.isEmpty()) {
                history.set(1, new UserMessage(renderExecution(ctx, remaining, doneSummary)));
            }
            log.info("[ToolLoop] round={} 已执行工具={} 历史消息数={} 累计调用={} 重复={}",
                    round + 1, out.getToolCalls().size(), history.size(),
                    callCounter.get(), dupCounter.get());
            if (callCounter.get() >= props.getMaxTotalCalls()) {
                log.warn("[ToolLoop] 达总调用数上限 {}，提前收尾 [calls={}, dup={}]",
                        props.getMaxTotalCalls(), callCounter.get(), dupCounter.get());
                break;
            }
        }

        // 触顶：不带工具强制总结（此时历史里只有压缩摘要，模型基于摘要作答）
        ChatResponse finalResp = callModel(history, List.of(), ctx.toolContext(), buildTaskLabel(remaining));
        AssistantMessage finalOut = resultOutput(finalResp);
        log.warn("[ToolLoop] 达最大轮数/调用数上限，强制总结收尾 [calls={}, dup={}]",
                callCounter.get(), dupCounter.get());
        return finalOut != null ? finalOut.getText() : null;
    }

    /** 钩子：本策略如何执行本轮的工具调用（串行 vs 并发），返回本轮 ToolResponseMessage */
    protected abstract ToolResponseMessage executeRound(AssistantMessage out, SubAgentToolLoopContext ctx,
            Map<String, String> doneSummary, List<SubTask> remaining,
            AtomicInteger callCounter, AtomicInteger dupCounter, AtomicReference<String> lastCallKey);

    // ============================================================
    // 共享支撑
    // ============================================================

    /** 用更新后的计划重渲染执行 prompt（历史摘要 + 剩余任务 + 本策略 toolCallRule） */
    protected String renderExecution(SubAgentToolLoopContext ctx, List<SubTask> remaining,
                                     Map<String, String> doneSummary) {
        SubTaskPlan updated = SubTaskPlan.builder()
                .userInput(ctx.plan().getUserInput())
                .currentResponse(ctx.plan().getCurrentResponse())
                .tasks(remaining)
                .historySummary(doneSummary)
                .userId(ctx.plan().getUserId())
                .conversationId(ctx.plan().getConversationId())
                .round(ctx.plan().getRound())
                .build();
        Map<String, String> vars = new LinkedHashMap<>(SubAgentPromptBuilder.buildVariables(updated));
        vars.put("toolCallRule", toolCallRule());
        return ctx.promptService().render(PromptKeys.SUBAGENT_EXECUTION, vars);
    }

    /** 主循环规划调用：打 subagent-exec 标记（携带剩余任务工具名清单） */
    protected ChatResponse callModel(List<Message> history, List<ToolCallback> callbacks,
                                     Map<String, Object> toolContext, String taskLabel) {
        ChatModelObservationConventionConfig.mark("subagent-exec", taskLabel);
        try {
            return chatModel.call(new Prompt(history, buildOptions(callbacks, toolContext)));
        } finally {
            ChatModelObservationConventionConfig.clear();
        }
    }

    protected String buildTaskLabel(List<SubTask> tasks) {
        if (tasks == null || tasks.isEmpty()) {
            return null;
        }
        String joined = tasks.stream()
                .map(SubTask::getToolName)
                .filter(Objects::nonNull)
                .distinct()
                .collect(Collectors.joining(","));
        if (joined.isBlank()) {
            return null;
        }
        return TextUtils.truncate(joined, 40);
    }

    protected ToolCallingChatOptions buildOptions(List<ToolCallback> callbacks, Map<String, Object> toolContext) {
        return ToolCallingChatOptions.builder()
                .toolCallbacks(callbacks)
                .toolContext(toolContext)
                .internalToolExecutionEnabled(false)
                .build();
    }

    protected AssistantMessage resultOutput(ChatResponse resp) {
        Generation gen = resp != null ? resp.getResult() : null;
        return gen != null ? gen.getOutput() : null;
    }

    protected AssistantMessage assistantWithToolCalls(AssistantMessage out) {
        return AssistantMessage.builder()
                .content(out.getText())
                .toolCalls(out.getToolCalls())
                .build();
    }

    protected ToolCallback findByName(List<ToolCallback> callbacks, String name) {
        if (callbacks == null) {
            return null;
        }
        for (ToolCallback cb : callbacks) {
            if (name.equals(GuardedToolCallback.rawName(cb))) {
                return cb;
            }
        }
        return null;
    }

    /** 从剩余任务中移除已执行（含失败）的 toolName */
    protected void removeExecuted(List<SubTask> remaining, String toolName) {
        remaining.removeIf(t -> toolName.equals(t.getToolName()));
    }
}
