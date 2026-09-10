package com.hmdp.agent.plan.executionPlan.binding;

import com.hmdp.agent.plan.executionPlan.model.ToolMetadata;

import java.lang.reflect.Parameter;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/**
 * 按优先级解析单个工具的参数来源。
 *
 * <p>策略顺序固定为：{@code @FromTool} → 返回类型唯一匹配 → 参数名匹配；
 * 返回类型命中多个候选时直接判歧义，不进入参数名兜底。</p>
 */
public final class ToolParameterBindingResolver {

    private final List<ParameterSourceMatcher> matchers;

    public ToolParameterBindingResolver() {
        this.matchers = List.of(
            new FromToolSourceMatcher(),
            new UniqueReturnTypeSourceMatcher(),
            new DependencyNameSourceMatcher());
    }

    public ToolParameterBindingResult resolve(
            ToolMetadata toolMetadata,
            Function<String, ToolMetadata> metadataProvider) {
        if (toolMetadata == null || toolMetadata.getMethod() == null) {
            return ToolParameterBindingResult.empty();
        }

        List<ToolParameterBinding> bindings = new ArrayList<>();
        List<ParameterBindingIssue> issues = new ArrayList<>();
        List<String> dependencies = toolMetadata.getDependencies() == null
            ? List.of()
            : toolMetadata.getDependencies();

        for (Parameter parameter : toolMetadata.getMethod().getParameters()) {
            ParameterBindingContext context = new ParameterBindingContext(
                toolMetadata.getName(), parameter, dependencies, metadataProvider);

            for (ParameterSourceMatcher matcher : matchers) {
                ParameterSourceMatch match = matcher.match(context);
                if (!match.isApplicable()) {
                    continue;
                }
                if (match.isMatched()) {
                    bindings.add(match.binding());
                } else {
                    issues.add(match.issue());
                }
                break;
            }
        }

        return new ToolParameterBindingResult(List.copyOf(bindings), List.copyOf(issues));
    }
}
