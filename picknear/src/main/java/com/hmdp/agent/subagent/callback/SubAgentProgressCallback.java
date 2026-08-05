package com.hmdp.agent.subagent.callback;

/**
 * 子 Agent 进度回调接口。
 * <p>
 * 解耦 SubTaskAgent 与 SSE 实现，方便单测。
 * 所有方法均为 default（空实现），实现方按需覆盖。
 * </p>
 */
public interface SubAgentProgressCallback {

    /** 子 Agent 开始执行前 */
    default void onExecuteStart(int taskCount) {}

    /** 工具调用状态变更 */
    default void onToolCall(String toolName, String status) {}

    /** parseResult 完成后、即将返回摘要前 */
    default void onMergeStart() {}

    /** 异常发生 */
    default void onError(String message) {}
}
