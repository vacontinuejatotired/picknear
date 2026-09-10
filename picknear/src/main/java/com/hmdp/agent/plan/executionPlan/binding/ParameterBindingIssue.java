package com.hmdp.agent.plan.executionPlan.binding;

/**
 * 参数绑定校验问题。
 *
 * <p>歧义、显式来源未声明等情况在规划期直接转成可读问题，禁止静默执行。</p>
 *
 * @param targetToolName 下游工具名
 * @param parameterName 下游形参名
 * @param message 给 LLM/日志看的可读原因
 */
public record ParameterBindingIssue(
        String targetToolName,
        String parameterName,
        String message) {
}
