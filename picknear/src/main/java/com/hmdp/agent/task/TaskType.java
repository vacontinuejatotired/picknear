package com.hmdp.agent.task;

/**
 * 子任务类型。
 * <p>
 * TOOL_CALL：调用 @Tool 方法，复用 GuardedToolCallback 执行。<br>
 * LLM_REASON：已废弃，仅保留供回退路径使用。新代码应让子 Agent 自行摘要。
 * </p>
 */
public enum TaskType {
    TOOL_CALL,
    /** @deprecated 由 SubTaskAgent 替代，仅回退路径（feature.subagent.enabled=false）使用 */
    @Deprecated
    LLM_REASON
}
