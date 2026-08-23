package com.hmdp.agent.execution;

import com.hmdp.agent.config.ChatModelObservationConventionConfig;
import com.hmdp.agent.config.SubTaskProperties;
import com.hmdp.agent.guard.GuardedToolCallback;
import com.hmdp.agent.prompt.PromptKeys;
import com.hmdp.agent.prompt.PromptService;
import com.hmdp.agent.subagent.loop.ToolExecutionStrategy;
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
 * 工具执行门面（瘦身后）。
 * <p>
 * 职责：组装上下文 → 调重试执行 → 调结果解析（3 个职责）。
 * 工具筛选下沉到 Strategy，Prompt 构建下沉到 ExecutionPromptBuilder。
 * </p>
 */
@Slf4j
@Component
public class ToolExecutionFacade {

    @Resource
    private ToolExecutionStrategy toolLoop;

    @Resource
    private ToolBeanCollector toolBeanCollector;

    @Resource
    private SubTaskProperties properties;

    @Resource
    private PromptService promptService;

    @Resource
    private ResultParser resultParser;

    @Resource
    private RetryRunner retryRunner;

    public SubTaskResult execute(SubTaskExecution execution) {
        long start = System.currentTimeMillis();
        var plan = execution.getPlan();
        var tasks = plan.getTasks();
        var callback = execution.getCallback();
        var props = execution.getProperties() != null ? execution.getProperties() : properties;

        List<String> toolNames = tasks.stream()
                .map(t -> t.getToolName())
                .filter(Objects::nonNull)
                .distinct()
                .toList();

        log.info("[SubAgent] 开始执行 [round={}, taskCount={}, tasks={}, userId={}]",
                plan.getRound(), tasks.size(), toolNames, plan.getUserId());

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

        if (callback != null) callback.onExecuteStart(tasks.size());

        String toolCallRule = toolLoop.toolCallRule();
        Map<String, String> execVars = new LinkedHashMap<>(SubAgentPromptBuilder.buildVariables(plan));
        execVars.put("toolCallRule", toolCallRule);
        String prompt = promptService.render(PromptKeys.SUBAGENT_EXECUTION, execVars);
        Map<String, String> sysVars = new HashMap<>();
        sysVars.put("userId", plan.getUserId() != null ? String.valueOf(plan.getUserId()) : "");
        sysVars.put("toolCallRule", toolCallRule);
        String systemText = promptService.render(PromptKeys.SYSTEM_SUBAGENT, sysVars);

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

        SubTaskResult result = resultParser.parse(content, start);

        if (callback != null) callback.onMergeStart();

        log.info("[SubAgent] 执行完成 [round={}, elapsed={}ms, allSuccess={}, toolResults={}]",
                plan.getRound(), result.getExecutionTimeMs(), result.isAllSuccess(),
                result.getRawResults() != null ? result.getRawResults().keySet() : "none");

        return result;
    }

    private ToolCallback[] filterCallbacks(List<String> toolNames) {
        ToolCallback[] all = toolBeanCollector.getToolCallbacks();
        if (all == null) return new ToolCallback[0];
        Set<String> allowed = new HashSet<>(toolNames);
        return Arrays.stream(all)
                .filter(cb -> allowed.contains(GuardedToolCallback.rawName(cb)))
                .toArray(ToolCallback[]::new);
    }
}
