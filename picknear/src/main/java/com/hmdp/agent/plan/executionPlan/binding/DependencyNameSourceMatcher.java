package com.hmdp.agent.plan.executionPlan.binding;

import java.util.List;

/**
 * 参数名与依赖工具名一致的兜底规则。
 *
 * <p>依赖参数名在 {@code -parameters} 编译选项下才可稳定获取。</p>
 */
public final class DependencyNameSourceMatcher implements ParameterSourceMatcher {

    @Override
    public ParameterSourceMatch match(ParameterBindingContext context) {
        String parameterName = context.parameter().getName();
        List<String> matchingTools = context.declaredDependencies().stream()
            .filter(parameterName::equals)
            .toList();

        if (matchingTools.size() != 1) {
            return ParameterSourceMatch.none();
        }

        String sourceTool = matchingTools.get(0);
        return ParameterSourceMatch.matched(new ToolParameterBinding(
            parameterName, sourceTool, ParameterSource.PARAMETER_NAME));
    }
}
