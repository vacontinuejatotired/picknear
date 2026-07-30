package com.hmdp.agent.task;

import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 子任务执行报告。
 * <p>
 * 跟踪多轮循环中的任务完成/失败状态。
 * 失败重试超过阈值的工具自动进入 {@code finalFailed} 黑名单，避免无谓重试。
 * </p>
 */
public class TaskReport {

    private final List<SubTask> completed = new CopyOnWriteArrayList<>();
    private final List<SubTask> failed = new CopyOnWriteArrayList<>();
    private final Set<String> finalFailed = ConcurrentHashMap.newKeySet();

    /** 记录本轮执行结果 */
    public void record(List<SubTask> tasks) {
        for (SubTask t : tasks) {
            if (t.getStatus() == SubTaskStatus.COMPLETED) {
                completed.add(t);
            }
            if (t.getStatus() == SubTaskStatus.FAILED) {
                failed.add(t);
                // 重试 >= 1 次后加入最终失败黑名单，后续不再尝试
                if (t.getRetryCount() >= 1) {
                    finalFailed.add(t.getToolName());
                }
            }
        }
    }

    /** 工具是否已完成 */
    public boolean isCompleted(String toolName) {
        return completed.stream().anyMatch(t -> toolName.equals(t.getToolName()));
    }

    /** 工具是否已失败 */
    public boolean hasFailed(String toolName) {
        return failed.stream().anyMatch(t -> toolName.equals(t.getToolName()));
    }

    /** 工具是否已进入终极失败黑名单（放弃重试） */
    public boolean isFinalFailed(String toolName) {
        return finalFailed.contains(toolName);
    }

    /** 获取需要重试的失败工具名（过滤掉 finalFailed） */
    public List<String> getFailedToolNames() {
        return failed.stream()
                .map(SubTask::getToolName)
                .filter(t -> !finalFailed.contains(t))
                .distinct()
                .toList();
    }

    /** 获取工具的已重试次数 */
    public int getRetryCount(String toolName) {
        return (int) failed.stream()
                .filter(t -> toolName.equals(t.getToolName()))
                .count();
    }

    /** 获取所有已完成任务 */
    public List<SubTask> getCompleted() {
        return completed;
    }

    /** 获取所有失败任务 */
    public List<SubTask> getFailed() {
        return failed;
    }
}
