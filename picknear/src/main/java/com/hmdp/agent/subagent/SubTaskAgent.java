package com.hmdp.agent.subagent;

import com.hmdp.agent.config.ChatModelObservationConventionConfig;
import com.hmdp.agent.config.SubTaskProperties;
import com.hmdp.agent.guard.GuardedToolCallback;
import com.hmdp.agent.prompt.PromptKeys;
import com.hmdp.agent.prompt.PromptService;
import com.hmdp.agent.subagent.loop.SubAgentToolLoop;
import com.hmdp.agent.subagent.model.SubTaskExecution;
import com.hmdp.agent.subagent.model.SubTaskPlan;
import com.hmdp.agent.subagent.model.SubTaskResult;
import com.hmdp.agent.subagent.prompt.SubAgentPromptBuilder;
import com.hmdp.agent.tool.ToolBeanCollector;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 子任务执行 Agent。
 * <p>
 * 职责：接收 SubTaskExecution → 按 tasks 筛选 ToolCallback →
 * {@link com.hmdp.agent.subagent.loop.SubAgentToolLoop} 策略执行工具循环（结果经 LLM 压缩后入上下文）→
 * 解析回复数据快照 → 返回 SubTaskResult。
 * 执行 Prompt 与系统提示词经 {@link PromptService} 外置（Langfuse → 内置兜底）。
 * </p>
 * <p>
 * 拆分归属：重试编排 → {@link SubAgentRetryRunner}；回复解析 → {@link SubTaskResultParser}。
 * </p>
 */
@Slf4j
@Component
public class SubTaskAgent {

    @Resource
    private SubAgentToolLoop toolLoop;

    @Resource
    private ToolBeanCollector toolBeanCollector;

    @Resource
    private SubTaskProperties properties;

    @Resource
    private PromptService promptService;

    @Resource
    private SubTaskResultParser resultParser;

    @Resource
    private SubAgentRetryRunner retryRunner;

    /**
     * 执行子任务计划，返回摘要。
     *
     * @param execution 执行上下文（含 plan、callback、配置）
     * @return 执行结果（含摘要 + 原始数据快照）
     */
    public SubTaskResult execute(SubTaskExecution execution) {
        long start = System.currentTimeMillis();
        var plan = execution.getPlan();
        var tasks = plan.getTasks();
        var callback = execution.getCallback();
        var props = execution.getProperties() != null ? execution.getProperties() : properties;

        // 提取去重后的工具名列表
        List<String> toolNames = tasks.stream()
                .map(t -> t.getToolName())
                .filter(Objects::nonNull)
                .distinct()
                .toList();

        // ═════ 入口结构化日志 ═════
        log.info("[SubAgent] 开始执行 [round={}, taskCount={}, tasks={}, userId={}]",
                plan.getRound(), tasks.size(), toolNames, plan.getUserId());

        // 1. 按 plan.tasks 筛选工具（同名工具去重，只保留一个 ToolCallback 实例）
        ToolCallback[] filteredCallbacks = filterCallbacks(toolNames);
        if (filteredCallbacks.length == 0) {
            log.warn("[SubAgent] 无可用的 ToolCallback [round={}, requested={}]",
                    plan.getRound(), toolNames);
            if (callback != null) callback.onError("无可用的工具");
            return SubTaskResult.builder()
                    .summary(plan.getCurrentResponse())
                    .allSuccess(false)
                    .errors(Map.of("subAgent", "无可用的工具"))
                    .executionTimeMs(System.currentTimeMillis() - start)
                    .build();
        }

        // 2. 推送：开始执行
        if (callback != null) callback.onExecuteStart(tasks.size());

        // 3. 构建执行 Prompt（模板外置，PromptService 渲染 {{var}}）。
        //    工具调用规则文本按激活的 SubAgentToolLoop 策略注入（serial=逐个；batch=独立可同时）
        String toolCallRule = toolLoop.toolCallRule();
        Map<String, String> execVars = new LinkedHashMap<>(SubAgentPromptBuilder.buildVariables(plan));
        execVars.put("toolCallRule", toolCallRule);
        String prompt = promptService.render(PromptKeys.SUBAGENT_EXECUTION, execVars);
        // 系统提示词渲染一次（缓存命中后开销≈0），重试循环不重复渲染
        Map<String, String> sysVars = new HashMap<>();
        sysVars.put("userId", plan.getUserId() != null ? String.valueOf(plan.getUserId()) : "");
        sysVars.put("toolCallRule", toolCallRule);
        String systemText = promptService.render(PromptKeys.SYSTEM_SUBAGENT, sysVars);

        // 4. 带退避重试调用（含总超时保护），携带 userId / conversationId 作为 ToolContext。
        //    ChatModel 观察标记由 SubAgentToolLoop（subagent-exec）与 ToolResultCompressor
        //    （subagent-compress）各自设置，Langfuse generation 名按功能区分（见 ChatModelObservationConventionConfig）。
        String content;
        try {
            content = retryRunner.executeWithRetry(systemText, prompt, plan, filteredCallbacks, props,
                    start, plan.getUserId(), plan.getConversationId());
        } finally {
            ChatModelObservationConventionConfig.clear();
        }

        if (content == null) {
            long elapsed = System.currentTimeMillis() - start;
            log.warn("[SubAgent] 执行失败（重试耗尽或超时） [round={}, elapsed={}ms]",
                    plan.getRound(), elapsed);
            if (callback != null) callback.onError("服务暂时不可用");
            return SubTaskResult.builder()
                    .summary("⚠️ 服务暂时不可用，请稍后重试。")
                    .allSuccess(false)
                    .errors(Map.of("subAgent", "重试耗尽"))
                    .executionTimeMs(elapsed)
                    .build();
        }

        // 5. 解析结果（从回复中提取 JSON 数据快照 + 数据截断 + 降级兜底）
        SubTaskResult result = resultParser.parse(content, start);

        // 6. 推送：数据汇总完成
        if (callback != null) callback.onMergeStart();

        // ═════ 出口结构化日志（只打 key 列表，不打全量数据） ═════
        log.info("[SubAgent] 执行完成 [round={}, elapsed={}ms, allSuccess={}, toolResults={}]",
                plan.getRound(), result.getExecutionTimeMs(), result.isAllSuccess(),
                result.getRawResults() != null ? result.getRawResults().keySet() : "none");

        return result;
    }

    /**
     * 根据任务规划中的 toolName 筛选 ToolCallback。
     * 防止子 Agent 调用本轮未规划的工具（如写操作工具）。
     */
    private ToolCallback[] filterCallbacks(List<String> toolNames) {
        ToolCallback[] all = toolBeanCollector.getToolCallbacks();
        if (all == null) return new ToolCallback[0];
        // 按原始名过滤（不触发外置描述解析），只保留本轮计划内的工具；
        // 外置描述由 ChatClient 在构造函数 schema 时对被保留的工具按需解析
        Set<String> allowed = new HashSet<>(toolNames);
        return Arrays.stream(all)
                .filter(cb -> allowed.contains(GuardedToolCallback.rawName(cb)))
                .toArray(ToolCallback[]::new);
    }
}
