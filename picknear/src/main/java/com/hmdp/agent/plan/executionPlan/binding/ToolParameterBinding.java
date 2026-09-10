package com.hmdp.agent.plan.executionPlan.binding;

/**
 * 工具调用参数绑定。
 *
 * <p>表示下游工具的一个形参由哪个上游工具的结果注入，以及按哪条规则匹配得到。</p>
 *
 * @param parameterName 下游工具形参名（LLM arguments JSON 中的 key）
 * @param sourceToolName 上游来源工具名
 * @param source 来源匹配规则
 */
public record ToolParameterBinding(
        String parameterName,
        String sourceToolName,
        ParameterSource source) {
}
