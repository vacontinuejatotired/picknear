package com.hmdp.agent.plan.executionPlan.binding;

/**
 * 单个匹配策略的解析结果。
 */
public record ParameterSourceMatch(
        ToolParameterBinding binding,
        ParameterBindingIssue issue) {

    public static ParameterSourceMatch matched(ToolParameterBinding binding) {
        return new ParameterSourceMatch(binding, null);
    }

    public static ParameterSourceMatch issue(ParameterBindingIssue issue) {
        return new ParameterSourceMatch(null, issue);
    }

    public static ParameterSourceMatch none() {
        return new ParameterSourceMatch(null, null);
    }

    public boolean isApplicable() {
        return binding != null || issue != null;
    }

    public boolean isMatched() {
        return binding != null;
    }
}
