package com.hmdp.agent.subagent;

import com.hmdp.agent.config.ChatModelObservationConventionConfig;
import com.hmdp.agent.guard.ConfirmRequiredException;
import com.hmdp.agent.guard.GuardedToolCallback;
import com.hmdp.agent.prompt.PromptKeys;
import com.hmdp.agent.prompt.PromptService;
import com.hmdp.agent.subagent.model.SubTaskPlan;
import com.hmdp.agent.subagent.prompt.SubAgentPromptBuilder;
import com.hmdp.agent.task.SubTask;
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
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

/**
 * 手动工具循环执行器（结构性解决上下文滚雪球，设计文档 §4.4）。
 * <p>
 * 与 Spring AI 内置 tool-call 循环（每轮把完整工具结果追加进历史、后续全量重发）不同：
 * 本执行器用 {@code ChatModel} 手动驱动循环，关闭内置执行，每轮：
 * ① LLM 返回 tool_calls → ② Java 执行工具（仍走 GuardedToolCallback，安全不变）
 * → ③ {@link ToolResultCompressor} 把结果压成要点摘要 → ④ 只把压缩摘要存入历史。
 * 原始结果绝不进上下文，每轮增量 ≈ 压缩摘要长度，上下文不随工具数/轮数滚雪球。
 * </p>
 */
@Slf4j
@Component
public class ToolCallLoopExecutor {

    @Resource
    private ChatModel chatModel;

    @Resource
    private ToolResultCompressor compressor;

    /**
     * 执行手动工具循环，返回最终 assistant 文本（无工具调用时的回复）。
     *
     * @param callbacks      本轮可用工具（Guard 包装后的 ToolCallback）
     * @param systemText     子 Agent 系统提示词
     * @param initialPrompt  首轮 user 消息（已渲染的执行 prompt，可能含重试错误注入）
     * @param plan           子任务计划（任务列表 / 跨轮历史摘要 / 用户输入等，供每轮重渲染）
     * @param promptService  执行 prompt 渲染器（每轮用更新后的历史摘要 + 剩余任务重渲染）
     * @param toolContext    ToolContext（userId / conversationId，Guard 层取用）
     * @param maxToolRounds  循环最大轮数（触顶后强制不带工具补一次总结）
     * @param compressLength 工具结果压缩摘要最大字符数
     * @return 最终回复文本；循环异常/无法生成时可能为 null
     */
    public String execute(List<ToolCallback> callbacks, String systemText, String initialPrompt,
                          SubTaskPlan plan, PromptService promptService,
                          Map<String, Object> toolContext, int maxToolRounds, int compressLength,
                          int maxTotalCalls) {
        int rounds = Math.max(1, maxToolRounds);
        List<SubTask> remaining = new ArrayList<>(plan.getTasks());
        // 已完成工具摘要：先带跨轮历史，再逐轮追加本轮压缩结果
        Map<String, String> doneSummary = new LinkedHashMap<>(
                plan.getHistorySummary() == null ? Map.of() : plan.getHistorySummary());
        List<Message> history = new ArrayList<>();
        history.add(new SystemMessage(systemText));
        history.add(new UserMessage(initialPrompt));

        // 重复/预算观测（不抑制调用，只记录 + 预算硬顶）
        AtomicInteger callCounter = new AtomicInteger(0);
        AtomicInteger dupCounter = new AtomicInteger(0);
        AtomicReference<String> lastCallKey = new AtomicReference<>();

        for (int round = 0; round < rounds; round++) {
            ChatResponse resp = callModel(history, callbacks, toolContext, buildTaskLabel(remaining));
            AssistantMessage out = resultOutput(resp);
            if (out == null) return null;
            if (!out.hasToolCalls()) {
                return out.getText();
            }
            history.add(assistantWithToolCalls(out));
            history.add(executeAndCompress(out, callbacks, toolContext, compressLength,
                    doneSummary, remaining, callCounter, dupCounter, lastCallKey));
            // 每轮用更新后的计划重渲染 user 消息：历史摘要反映已完成工具、任务列表缩到剩余，
            // 让每轮上下文的「历史执行摘要 / 本轮待执行任务」如实反映进度（而非恒为"（无）"）
            if (!doneSummary.isEmpty()) {
                history.set(1, new UserMessage(renderExecution(promptService, plan, remaining, doneSummary)));
            }
            log.info("[ToolLoop] round={} 已执行工具={} 历史消息数={} 累计调用={} 重复={}",
                    round + 1, out.getToolCalls().size(), history.size(),
                    callCounter.get(), dupCounter.get());
            if (callCounter.get() >= maxTotalCalls) {
                log.warn("[ToolLoop] 达总调用数上限 {}，提前收尾 [calls={}, dup={}]",
                        maxTotalCalls, callCounter.get(), dupCounter.get());
                break;
            }
        }

        // 触顶：不带工具强制总结（此时历史里只有压缩摘要，模型基于摘要作答）
        ChatResponse finalResp = callModel(history, List.of(), toolContext, buildTaskLabel(remaining));
        AssistantMessage finalOut = resultOutput(finalResp);
        log.warn("[ToolLoop] 达最大轮数/调用数上限，强制总结收尾 [calls={}, dup={}]",
                callCounter.get(), dupCounter.get());
        return finalOut != null ? finalOut.getText() : null;
    }

    /** 用更新后的计划重渲染执行 prompt（历史摘要 + 剩余任务等反映真实进度） */
    private String renderExecution(PromptService promptService, SubTaskPlan plan,
                                   List<SubTask> remaining, Map<String, String> doneSummary) {
        SubTaskPlan updated = SubTaskPlan.builder()
                .userInput(plan.getUserInput())
                .currentResponse(plan.getCurrentResponse())
                .tasks(remaining)
                .historySummary(doneSummary)
                .userId(plan.getUserId())
                .conversationId(plan.getConversationId())
                .round(plan.getRound())
                .build();
        return promptService.render(PromptKeys.SUBAGENT_EXECUTION,
                SubAgentPromptBuilder.buildVariables(updated));
    }

    /**
     * 主循环规划调用：打 subagent-exec 标记（携带剩余任务工具名清单），
     * Langfuse generation 名 = subagent-exec-{任务}-chat <model>。
     */
    private ChatResponse callModel(List<Message> history, List<ToolCallback> callbacks,
                                   Map<String, Object> toolContext, String taskLabel) {
        ChatModelObservationConventionConfig.mark("subagent-exec", taskLabel);
        try {
            return chatModel.call(new Prompt(history, buildOptions(callbacks, toolContext)));
        } finally {
            ChatModelObservationConventionConfig.clear();
        }
    }

    /**
     * 剩余任务 → 工具名清单（逗号连接，限长），编码进 generation 名，
     * 让 Langfuse 控制台免点击看出"这轮在驱动哪些任务"。无任务/空清单返回 null。
     */
    private String buildTaskLabel(List<SubTask> tasks) {
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

    private ToolCallingChatOptions buildOptions(List<ToolCallback> callbacks, Map<String, Object> toolContext) {
        return ToolCallingChatOptions.builder()
                .toolCallbacks(callbacks)
                .toolContext(toolContext)
                .internalToolExecutionEnabled(false)
                .build();
    }

    private AssistantMessage resultOutput(ChatResponse resp) {
        Generation gen = resp != null ? resp.getResult() : null;
        return gen != null ? gen.getOutput() : null;
    }

    private AssistantMessage assistantWithToolCalls(AssistantMessage out) {
        return AssistantMessage.builder()
                .content(out.getText())
                .toolCalls(out.getToolCalls())
                .build();
    }

    /** 执行本轮所有工具调用，压缩结果并组装 ToolResponseMessage（只含压缩摘要）；
     *  同步把压缩摘要写入 doneSummary、把已执行任务移出 remaining（供每轮重渲染）。
     *  观测：累计调用数 + 同工具同参数连续重复次数（不抑制调用，仅记录健康指标）。 */
    private ToolResponseMessage executeAndCompress(AssistantMessage out, List<ToolCallback> callbacks,
                                                   Map<String, Object> toolContext, int compressLength,
                                                   Map<String, String> doneSummary, List<SubTask> remaining,
                                                   AtomicInteger callCounter, AtomicInteger dupCounter,
                                                   AtomicReference<String> lastCallKey) {
        ToolContext ctx = new ToolContext(toolContext == null ? Map.of() : toolContext);
        List<ToolResponse> responses = new ArrayList<>();
        for (AssistantMessage.ToolCall tc : out.getToolCalls()) {
            ToolCallback cb = findByName(callbacks, tc.name());
            if (cb == null) {
                responses.add(new ToolResponse(tc.id(), tc.name(), "错误：工具不可用"));
                continue;
            }
            // 同工具同参数 = 连续重复标记；数据可能已被工具流之外修改，不抑制，仅记录
            String key = tc.name() + "|" + (tc.arguments() == null ? "" : tc.arguments());
            if (key.equals(lastCallKey.get())) {
                dupCounter.incrementAndGet();
                log.warn("[ToolLoop] 检测到同工具同参数连续重复调用 [tool={}]，不抑制（数据可能已变更）", tc.name());
            } else {
                lastCallKey.set(key);
            }
            try {
                String raw = cb.call(tc.arguments(), ctx);
                String compact = compressor.compress(raw, tc.name(), compressLength);
                responses.add(new ToolResponse(tc.id(), tc.name(), compact));
                doneSummary.put(tc.name(), TextUtils.truncate(compact, 50));
            } catch (ConfirmRequiredException e) {
                // 审批信号：冒泡到 TaskPlanner 生成审批记录并暂停（不重试、不入历史）
                throw e;
            } catch (Exception e) {
                // 普通工具异常：压成一行错误入历史，LLM 继续处理（对齐旧行为）
                log.warn("[ToolLoop] 工具执行失败 [tool={}, err={}]", tc.name(), e.getMessage());
                responses.add(new ToolResponse(tc.id(), tc.name(), "错误：" + e.getMessage()));
                doneSummary.put(tc.name(), "执行失败：" + e.getMessage());
            }
            callCounter.incrementAndGet();
            removeExecuted(remaining, tc.name());
        }
        return ToolResponseMessage.builder().responses(responses).build();
    }

    /** 从剩余任务中移除已执行（含失败）的 toolName，保证每轮「本轮待执行任务」只列未执行的 */
    private void removeExecuted(List<SubTask> remaining, String toolName) {
        remaining.removeIf(t -> toolName.equals(t.getToolName()));
    }

    private ToolCallback findByName(List<ToolCallback> callbacks, String name) {
        if (callbacks == null) return null;
        for (ToolCallback cb : callbacks) {
            if (name.equals(GuardedToolCallback.rawName(cb))) {
                return cb;
            }
        }
        return null;
    }
}
