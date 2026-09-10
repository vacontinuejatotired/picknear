package com.hmdp.agent.plan.executionPlan.binding;

/**
 * 参数来源匹配策略。
 *
 * <p>按优先级顺序执行，命中或发现歧义即停止；{@code none} 表示本规则不适用，继续下一条。</p>
 */
@FunctionalInterface
public interface ParameterSourceMatcher {

    ParameterSourceMatch match(ParameterBindingContext context);
}
