package com.hmdp.agent.task.model;

/**
 * @deprecated 请使用 {@link com.hmdp.agent.plan.model.TaskType}。
 * 本类保留仅为编译兼容，将在下一批次删除。
 */
@Deprecated(forRemoval = true)
public enum TaskType {
    TOOL_CALL,
    @Deprecated
    LLM_REASON
}
