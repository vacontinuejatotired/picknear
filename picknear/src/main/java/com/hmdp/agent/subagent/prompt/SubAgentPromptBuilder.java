package com.hmdp.agent.subagent.prompt;

import com.hmdp.agent.subagent.model.SubTaskPlan;
import com.hmdp.agent.task.SubTask;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

/**
 * 子 Agent Prompt 构建器。
 * <p>
 * 根据 SubTaskPlan 构建执行 Prompt，组装任务描述、参数约束段、历史摘要。
 * </p>
 */
public final class SubAgentPromptBuilder {

    private SubAgentPromptBuilder() {}

    /**
     * 构建执行 Prompt。
     *
     * @param plan 子 Agent 执行计划
     * @return 完整 Prompt 字符串
     */
    public static String build(SubTaskPlan plan) {
        String historyText = buildHistorySummaryText(plan.getHistorySummary());
        String paramConstraints = buildParamConstraints(plan.getTasks());
        String tasksDesc = buildTasksDesc(plan.getTasks());

        return String.format(
                SubAgentPromptTemplate.EXECUTION_PROMPT,
                plan.getUserInput(),
                plan.getCurrentResponse(),
                historyText,
                paramConstraints,
                tasksDesc,
                SubAgentPromptTemplate.SNAPSHOT_BEGIN,
                String.valueOf(SubAgentPromptTemplate.RAW_DATA_MAX_LENGTH),
                SubAgentPromptTemplate.SNAPSHOT_END,
                String.valueOf(SubAgentPromptTemplate.RAW_DATA_MAX_LENGTH)
        );
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
        if (s == null || s.length() <= max) return s;
        return s.substring(0, max) + "...";
    }
}
