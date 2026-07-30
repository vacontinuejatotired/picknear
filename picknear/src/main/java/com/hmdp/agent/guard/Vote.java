package com.hmdp.agent.guard;

/**
 * 工具守卫策略的投票结果
 * <p>
 * 每个 {@link ToolGuardPolicy} 针对一次工具调用返回一个投票，
 * {@link ToolGuardManager} 汇总所有投票做出最终决策。
 * </p>
 */
public enum Vote {

    /**
     * 放行 — 策略明确认为本次调用是安全的
     */
    ALLOW,

    /**
     * 确认 — 策略认为存在风险，需要用户手动确认
     */
    CONFIRM,

    /**
     * 拦截 — 策略认定本次调用高风险，直接拒绝执行
     */
    BLOCK,

    /**
     * 弃权 — 策略不关心本次调用，交由其他策略决策
     */
    ABSTAIN
}
