package com.hmdp.agent.plan.executionPlan.binding;

import com.hmdp.agent.plan.executionPlan.annotation.FromTool;
import com.hmdp.agent.plan.executionPlan.model.ToolMetadata;

/**
 * {@code @FromTool} 显式来源规则，优先级最高。
 */
public final class FromToolSourceMatcher implements ParameterSourceMatcher {

    @Override
    public ParameterSourceMatch match(ParameterBindingContext context) {
        FromTool fromTool = context.parameter().getAnnotation(FromTool.class);
        if (fromTool == null) {
            return ParameterSourceMatch.none();
        }

        String sourceTool = fromTool.value();
        String parameterName = context.parameter().getName();

        if (!context.declaredDependencies().contains(sourceTool)) {
            return ParameterSourceMatch.issue(new ParameterBindingIssue(
                context.targetToolName(), parameterName,
                "参数 [" + parameterName + "] 通过 @FromTool 指定来源 [" + sourceTool
                    + "]，但 [" + sourceTool + "] 未在 @DependsOn 中声明"));
        }

        ToolMetadata sourceMetadata = context.metadataOf(sourceTool);
        if (sourceMetadata == null) {
            return ParameterSourceMatch.issue(new ParameterBindingIssue(
                context.targetToolName(), parameterName,
                "参数 [" + parameterName + "] 的 @FromTool 来源 [" + sourceTool + "] 未注册"));
        }

        Class<?> parameterType = context.parameter().getType();
        if (!parameterType.isAssignableFrom(sourceMetadata.getReturnType())) {
            return ParameterSourceMatch.issue(new ParameterBindingIssue(
                context.targetToolName(), parameterName,
                "参数 [" + parameterName + "] 类型 [" + parameterType.getSimpleName()
                    + "] 与 @FromTool 来源 [" + sourceTool + "] 返回类型 ["
                    + sourceMetadata.getReturnType().getSimpleName() + "] 不兼容"));
        }

        return ParameterSourceMatch.matched(new ToolParameterBinding(
            parameterName, sourceTool, ParameterSource.FROM_TOOL));
    }
}
