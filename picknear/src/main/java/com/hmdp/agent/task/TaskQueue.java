package com.hmdp.agent.task;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * 子任务队列（已废弃，保留供回退路径使用）。
 * <p>
 * 新代码使用 {@link com.hmdp.agent.subagent.SubTaskAgent} 替代。
 * </p>
 * <p>
 * 管理所有 SubTask 的状态流转。依赖判断使用"终结状态"（COMPLETED 或 FAILED），
 * 避免 LLM_REASON 因依赖的 TOOL_CALL 失败而永远卡在 PENDING。
 * </p>
 */
@Deprecated
public class TaskQueue {

    private final Map<String, SubTask> taskMap = new ConcurrentHashMap<>();

    public TaskQueue(List<SubTask> tasks) {
        for (SubTask t : tasks) {
            taskMap.put(t.getId(), t);
        }
    }

    /**
     * 获取所有可执行任务：依赖已全部进入终结状态（COMPLETED 或 FAILED）。
     */
    public List<SubTask> getReadyTasks() {
        return taskMap.values().stream()
                .filter(t -> t.getStatus() == SubTaskStatus.PENDING)
                .filter(t -> t.getDependsOn() == null
                        || t.getDependsOn().isEmpty()
                        || t.getDependsOn().stream().allMatch(this::isTerminal))
                .peek(t -> t.setStatus(SubTaskStatus.READY))
                .collect(Collectors.toList());
    }

    /** 标记任务完成 */
    public void markDone(String id, Object result) {
        SubTask task = taskMap.get(id);
        if (task != null) {
            task.setStatus(SubTaskStatus.COMPLETED);
            task.setResult(result);
        }
    }

    /** 标记任务失败 */
    public void markFailed(String id, Object error) {
        SubTask task = taskMap.get(id);
        if (task != null) {
            task.setStatus(SubTaskStatus.FAILED);
            task.setResult(error);
            task.setRetryCount(task.getRetryCount() + 1);
        }
    }

    /** 是否全部完成（含失败） */
    public boolean isAllDone() {
        return taskMap.values().stream()
                .allMatch(t -> t.getStatus() == SubTaskStatus.COMPLETED
                        || t.getStatus() == SubTaskStatus.FAILED);
    }

    /** 获取全部任务 */
    public List<SubTask> getAllTasks() {
        return new ArrayList<>(taskMap.values());
    }

    /** 获取已完成任务 */
    public List<SubTask> getCompleted() {
        return taskMap.values().stream()
                .filter(t -> t.getStatus() == SubTaskStatus.COMPLETED)
                .collect(Collectors.toList());
    }

    /** 获取失败任务 */
    public List<SubTask> getFailed() {
        return taskMap.values().stream()
                .filter(t -> t.getStatus() == SubTaskStatus.FAILED)
                .collect(Collectors.toList());
    }

    /** 获取指定任务 */
    public SubTask getTask(String id) {
        return taskMap.get(id);
    }

    /**
     * 终结状态检查：COMPLETED 或 FAILED 都算终结，
     * LLM_REASON 不因部分 TOOL_CALL 失败而阻塞。
     */
    private boolean isTerminal(String taskId) {
        SubTask task = taskMap.get(taskId);
        if (task == null) return false;
        return task.getStatus() == SubTaskStatus.COMPLETED
                || task.getStatus() == SubTaskStatus.FAILED;
    }
}
