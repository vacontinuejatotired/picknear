package com.hmdp.agent.task.model;

/**
 * @deprecated 请使用 {@link com.hmdp.agent.plan.model.SubTaskStatus}。
 * 本类保留仅为编译兼容，将在下一批次删除。
 */
@Deprecated(forRemoval = true)
public enum SubTaskStatus {
    PENDING,
    READY,
    RUNNING,
    COMPLETED,
    FAILED
}
