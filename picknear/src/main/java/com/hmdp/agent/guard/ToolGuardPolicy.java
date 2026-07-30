package com.hmdp.agent.guard;

/**
 * 工具守卫策略 — 对一次工具调用进行风险评估
 * <p>
 * <strong>职责边界：</strong>
 * <ul>
 *   <li>只处理<em>无状态</em>逻辑：读取 YAML 配置、操作 Redis 计数器、判断正则匹配</li>
 *   <li><strong>不允许</strong>引入业务 Service（如 IBlogService、IOrderService）</li>
 *   <li>有状态的归属权校验由 {@code @RequiredDataPermission + AOP} 层处理</li>
 * </ul>
 * </p>
 * <p>
 * 所有实现类需标注 {@code @Component}，{@link ToolGuardManager} 在启动时自动收集。
 * </p>
 *
 * @see ToolGuardManager
 * @see Vote
 */
@FunctionalInterface
public interface ToolGuardPolicy {

    /**
     * 策略的唯一标识（用于日志和监控）
     */
    default String policyName() {
        return getClass().getSimpleName();
    }

    /**
     * 对本次工具调用进行投票
     *
     * @param context 工具调用上下文
     * @return 投票结果：ALLOW / CONFIRM / BLOCK / ABSTAIN
     */
    Vote vote(ToolInvocationContext context);
}
