package com.hmdp.agent.subagent.prompt;

import com.hmdp.agent.subagent.model.SubTaskPlan;
import com.hmdp.agent.task.model.SubTask;
import com.hmdp.agent.util.TextUtils;
import lombok.extern.slf4j.Slf4j;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

/**
 * 执行 Prompt 构建器。
 * <p>
 * 根据 SubTaskPlan 构建执行 Prompt，组装任务描述、参数约束段、历史摘要。
 * </p>
 */
@Slf4j
public final class ExecutionPromptBuilder {

    private ExecutionPromptBuilder() {}

    /**
     * 构建执行 Prompt 的变量集（模板已外置到 {@code agent.prompt.subagent.execution}，
     * 由 PromptService 渲染 {@code {{var}}} 占位符）。
     *
     * @param plan 子 Agent 执行计划
     * @return 变量名 → 值（含 wire-format 标记与长度常量注入）
     */
    public static Map<String, String> buildVariables(SubTaskPlan plan) {
        Map<String, String> vars = new LinkedHashMap<>();
        vars.put("userInput", plan.getUserInput());
        String currentResponse = plan.getCurrentResponse();
        if (log.isDebugEnabled()) {
            log.debug("[SubAgent] currentResponse.length={}",
                    currentResponse != null ? currentResponse.length() : 0);
        }
        vars.put("currentResponse",
                TextUtils.truncate(currentResponse, SubAgentPromptTemplate.CURRENT_RESPONSE_MAX_LENGTH));
        vars.put("historySummary", buildHistorySummaryText(plan.getHistorySummary()));
        vars.put("paramConstraints", buildParamConstraints(plan.getTasks()));
        vars.put("tasksDesc", buildTasksDesc(plan.getTasks()));
        vars.put("snapshotBegin", SubAgentPromptTemplate.SNAPSHOT_BEGIN);
        vars.put("snapshotEnd", SubAgentPromptTemplate.SNAPSHOT_END);
        vars.put("dataMaxLength", String.valueOf(SubAgentPromptTemplate.RAW_DATA_MAX_LENGTH));
        return vars;
    }

    // ══════════════════════════════════════════════════
    // 任务描述
    // ══════════════════════════════════════════════════

    /** 构建"本轮待执行任务"段 */
    static String buildTasksDesc(List<SubTask> tasks) {
        return IntStream.range(0, tasks.size())
                .mapToObj(i -> {
                    SubTask t = tasks.get(i);
                    String desc = String.format(SubAgentPromptTemplate.TASK_DESC_FORMAT,
                            i + 1, t.getDescription(), t.getToolName());
                    if (t.getParams() != null && !t.getParams().isEmpty()) {
                        String params = t.getParams().entrySet().stream()
                                .map(e -> "    " + e.getKey() + " = " + e.getValue())
                                .collect(Collectors.joining("\n"));
                        desc += "\n" + params;
                    }
                    return desc;
                })
                .collect(Collectors.joining("\n\n"));
    }

    // ══════════════════════════════════════════════════
    // 参数约束
    // ══════════════════════════════════════════════════

    /** 构建"参数约束"段 */
    static String buildParamConstraints(List<SubTask> tasks) {
        return tasks.stream()
                .filter(t -> t.getParams() != null && !t.getParams().isEmpty())
                .map(t -> {
                    String header = String.format(SubAgentPromptTemplate.PARAM_CONSTRAINT_FORMAT,
                            t.getToolName());
                    String params = t.getParams().entrySet().stream()
                            .map(e -> String.format(SubAgentPromptTemplate.PARAM_VALUE_FORMAT,
                                    e.getKey(), String.valueOf(e.getValue())))
                            .collect(Collectors.joining("\n"));
                    return header + "\n" + params;
                })
                .collect(Collectors.joining("\n"));
    }

    // ══════════════════════════════════════════════════
    // 历史摘要
    // ══════════════════════════════════════════════════

    /** 构建历史摘要文本 */
    static String buildHistorySummaryText(Map<String, String> historySummary) {
        if (historySummary == null || historySummary.isEmpty()) {
            return "（无）";
        }
        return historySummary.entrySet().stream()
                .map(e -> e.getKey() + ": " + truncate(e.getValue(), 50))
                .collect(Collectors.joining(SubAgentPromptTemplate.HISTORY_SEPARATOR));
    }

    /** 工具结果截断，用于历史摘要传递给下一轮规划 */
    public static String truncateResult(String result) {
        return truncate(result, 50);
    }

    private static String truncate(String s, int max) {
        return TextUtils.truncate(s, max);
    }
}
