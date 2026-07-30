package com.hmdp.agent.guard;

/**
 * 工具守卫的最终决策结果
 * <p>
 * 由 {@link ToolGuardManager} 汇总所有 {@link ToolGuardPolicy} 的
 * {@link Vote} 后生成。
 * </p>
 */
public class GuardResult {

    private final Decision decision;
    private final String reason;
    private final String policyName;

    public GuardResult(Decision decision, String reason, String policyName) {
        this.decision = decision;
        this.reason = reason;
        this.policyName = policyName;
    }

    public Decision getDecision() { return decision; }
    public String getReason() { return reason; }
    public String getPolicyName() { return policyName; }

    public boolean isBlocked() { return decision == Decision.BLOCK; }
    public boolean isConfirmed() { return decision == Decision.CONFIRM; }
    public boolean isAllowed() { return decision == Decision.ALLOW; }

    /**
     * 最终决策
     */
    public enum Decision {
        /** 放行 */
        ALLOW,
        /** 拦截 */
        BLOCK,
        /** 需用户确认 */
        CONFIRM
    }

    // ---- 工厂方法 ----

    public static GuardResult allow() {
        return new GuardResult(Decision.ALLOW, null, null);
    }

    public static GuardResult block(String reason, String policyName) {
        return new GuardResult(Decision.BLOCK, reason, policyName);
    }

    public static GuardResult confirm(String reason, String policyName) {
        return new GuardResult(Decision.CONFIRM, reason, policyName);
    }
}
