package com.hmdp.agent.plan.model;

/**
 * 子任务状态机。
 * <p>
 * PENDING → READY → RUNNING → COMPLETED / FAILED
 * </p>
 * <p>
 * 五态两用：<b>活链（ToolExecutionFacade/TaskPlanner）仅使用 PENDING/COMPLETED/FAILED
 * 终态</b>；READY/RUNNING 为回退链（legacy TaskQueue/TaskExecutor）专用中间态，
 * 活链代码不得写入这两个状态。
 * </p>
 */
public enum SubTaskStatus {
    PENDING,
    READY,
    RUNNING,
    COMPLETED,
    FAILED
}
