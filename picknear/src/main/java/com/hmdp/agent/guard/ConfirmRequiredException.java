package com.hmdp.agent.guard;

/**
 * 守卫 CONFIRM 决策的异常信号。
 * <p>
 * 由 {@link GuardedToolCallback} 在工具调用被投为 CONFIRM 时抛出，
 * 携带本次工具调用的完整上下文，一路穿透 Spring AI 工具调用链
 * （Spring AI 只包装 {@code ToolExecutionException}，普通 RuntimeException 原样冒泡），
 * 到 {@code TaskPlanner} 的专用 catch 处生成审批记录并暂停规划。
 * </p>
 */
public class ConfirmRequiredException extends RuntimeException {

    private final ToolInvocationContext context;
    private final String policyName;

    public ConfirmRequiredException(ToolInvocationContext context, String reason, String policyName) {
        super(reason);
        this.context = context;
        this.policyName = policyName;
    }

    /** 触发确认的工具调用上下文（toolName / arguments / conversationId / userId） */
    public ToolInvocationContext getContext() {
        return context;
    }

    /** 确认原因（与 getMessage() 同源） */
    public String getReason() {
        return getMessage();
    }

    /** 触发确认的守卫策略名 */
    public String getPolicyName() {
        return policyName;
    }
}
