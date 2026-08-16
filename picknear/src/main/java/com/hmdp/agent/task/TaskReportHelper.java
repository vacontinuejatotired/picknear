package com.hmdp.agent.task;

import com.hmdp.agent.subagent.model.SubTaskResult;
import com.hmdp.agent.subagent.prompt.SubAgentPromptBuilder;
import com.hmdp.agent.task.model.SubTask;
import com.hmdp.agent.task.model.TaskReport;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 规划执行的历史/结果聚合助手（从 TaskPlanner 拆出，纯逻辑无外部依赖）。
 * <p>
 * 职责：
 * <ul>
 *   <li>{@link #buildHistorySummary} — TaskReport → 历史摘要（子 Agent 路径用）</li>
 *   <li>{@link #recordHistory} — SubTaskResult → TaskReport（rawResults/errors 归一为 SubTask 列表）</li>
 *   <li>{@link #merge} — 回退路径聚合：取 LLM_REASON 结论作为最终输出</li>
 * </ul>
 * </p>
 */
@Slf4j
@Component
public class TaskReportHelper {

    /**
     * 从 TaskReport 中提取历史摘要（key=toolName, value=50字摘要）。
     */
    public Map<String, String> buildHistorySummary(TaskReport history) {
        Map<String, String> summary = new LinkedHashMap<>();
        for (SubTask t : history.getCompleted()) {
            summary.put(t.getToolName(),
                    SubAgentPromptBuilder.truncateResult(String.valueOf(t.getResult())));
        }
        return summary;
    }

    /**
     * 将 SubTaskResult 记录到 TaskReport。
     * <p>
     * 构建 SubTask 列表后调用 {@link TaskReport#record(List)}，
     * 确保 finalFailed 黑名单等逻辑正确触发。
     * </p>
     */
    public void recordHistory(TaskReport history, SubTaskResult result) {
        List<SubTask> subTasks = new ArrayList<>();

        if (result.getRawResults() != null) {
            for (var entry : result.getRawResults().entrySet()) {
                subTasks.add(SubTask.builder()
                        .toolName(entry.getKey())
                        .result(entry.getValue() != null ? entry.getValue().toString() : "")
                        .type(TaskType.TOOL_CALL)
                        .status(SubTaskStatus.COMPLETED)
                        .build());
            }
        }
        if (result.getErrors() != null) {
            for (var entry : result.getErrors().entrySet()) {
                subTasks.add(SubTask.builder()
                        .toolName(entry.getKey())
                        .result(entry.getValue())
                        .type(TaskType.TOOL_CALL)
                        .status(SubTaskStatus.FAILED)
                        .retryCount(1)  // 触发 TaskReport 的 finalFailed 黑名单
                        .build());
            }
        }

        if (!subTasks.isEmpty()) {
            history.record(subTasks);
        }
    }

    /**
     * 聚合结果（仅回退路径使用）。
     * 取 LLM_REASON 的执行结论作为最终输出。
     *
     * @param tasks 回退队列全部任务（TaskQueue.getAllTasks() 的结果）
     */
    public String merge(String currentResponse, List<SubTask> tasks) {
        String llmConclusion = tasks.stream()
                .filter(t -> t.getType() == TaskType.LLM_REASON
                        && t.getStatus() == SubTaskStatus.COMPLETED
                        && t.getResult() != null)
                .map(t -> t.getResult().toString())
                .findFirst()
                .orElse(null);
        if (llmConclusion != null) return llmConclusion;
        return currentResponse;
    }
}
