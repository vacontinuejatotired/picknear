package com.hmdp.agent.plan.executionPlan.binding;

import com.hmdp.agent.plan.executionPlan.model.ToolMetadata;

import java.util.List;

/**
 * 返回类型唯一匹配规则。
 *
 * <p>只有一个依赖工具返回类型兼容该形参时才绑定；多个候选直接产出歧义 Issue，
 * 不继续尝试参数名消歧，避免静默选错来源。</p>
 */
public final class UniqueReturnTypeSourceMatcher implements ParameterSourceMatcher {

    @Override
    public ParameterSourceMatch match(ParameterBindingContext context) {
        Class<?> parameterType = context.parameter().getType();
        List<String> matchingTools = context.declaredDependencies().stream()
            .filter(toolName -> {
                ToolMetadata meta = context.metadataOf(toolName);
                return meta != null && parameterType.isAssignableFrom(meta.getReturnType());
            })
            .sorted()
            .toList();

        String parameterName = context.parameter().getName();
        if (matchingTools.isEmpty()) {
            return ParameterSourceMatch.none();
        }
        if (matchingTools.size() > 1) {
            return ParameterSourceMatch.issue(new ParameterBindingIssue(
                context.targetToolName(), parameterName,
                "参数 [" + parameterName + "] 类型 [" + parameterType.getSimpleName()
                    + "] 存在歧义：匹配多个依赖工具 " + matchingTools
                    + "，请使用 @FromTool 显式指定来源"));
        }

        String sourceTool = matchingTools.get(0);
        return ParameterSourceMatch.matched(new ToolParameterBinding(
            parameterName, sourceTool, ParameterSource.RETURN_TYPE));
    }
}
