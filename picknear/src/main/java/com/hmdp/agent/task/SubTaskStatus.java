package com.hmdp.agent.task;

/**
 * 子任务状态机。
 * <p>
 * PENDING → READY → RUNNING → COMPLETED / FAILED
 * </p>
 */
public enum SubTaskStatus {
    PENDING,
    READY,
    RUNNING,
    COMPLETED,
    FAILED
}
