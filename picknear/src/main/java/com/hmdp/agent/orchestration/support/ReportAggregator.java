package com.hmdp.agent.orchestration.support;

import com.hmdp.agent.subagent.model.SubTaskResult;
import com.hmdp.agent.subagent.prompt.SubAgentPromptBuilder;
import com.hmdp.agent.plan.model.SubTask;
import com.hmdp.agent.plan.model.SubTaskStatus;
import com.hmdp.agent.plan.model.TaskReport;
import com.hmdp.agent.plan.model.TaskType;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 历史/结果聚合助手（纯逻辑无外部依赖）。
 * <p>
 * 职责：
 * <ul>
 *   <li>{@link #buildHistorySummary} — TaskReport → 历史摘要</li>
 *   <li>{@link #recordHistory} — SubTaskResult → TaskReport</li>
 *   <li>{@link #merge} — 回退路径聚合</li>
 * </ul>
 * </p>
 */
@Slf4j
@Component
public class ReportAggregator {

    public Map<String, String> buildHistorySummary(TaskReport history) {
        Map<String, String> summary = new LinkedHashMap<>();
        for (SubTask t : history.getCompleted()) {
            summary.put(t.getToolName(),
                    SubAgentPromptBuilder.truncateResult(String.valueOf(t.getResult())));
        }
        return summary;
    }

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
                        .retryCount(1)
                        .build());
            }
        }

        if (!subTasks.isEmpty()) {
            history.record(subTasks);
        }
    }

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
