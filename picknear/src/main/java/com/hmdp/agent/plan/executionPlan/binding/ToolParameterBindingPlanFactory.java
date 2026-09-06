package com.hmdp.agent.plan.executionPlan.binding;

import com.hmdp.agent.plan.executionPlan.model.ToolMetadata;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * 为当轮选中的工具生成参数绑定计划。
 *
 * <p>只解析选中工具，避免把全仓工具的绑定问题在启动期放大到无关路径。</p>
 */
public final class ToolParameterBindingPlanFactory {

    private final ToolParameterBindingResolver resolver;

    public ToolParameterBindingPlanFactory() {
        this.resolver = new ToolParameterBindingResolver();
    }

    public ToolParameterBindingPlan create(
            Collection<String> selectedTools,
            Function<String, ToolMetadata> metadataProvider) {
        Map<String, List<ToolParameterBinding>> bindingsByTool = new LinkedHashMap<>();
        List<ParameterBindingIssue> issues = new ArrayList<>();

        for (String toolName : new LinkedHashSet<>(selectedTools)) {
            ToolMetadata metadata = metadataProvider.apply(toolName);
            if (metadata == null || metadata.getMethod() == null) {
                continue;
            }

            ToolParameterBindingResult result = resolver.resolve(metadata, metadataProvider);
            if (!result.bindings().isEmpty()) {
                bindingsByTool.put(toolName, result.bindings());
            }
            issues.addAll(result.issues());
        }

        return new ToolParameterBindingPlan(
            Map.copyOf(bindingsByTool),
            List.copyOf(issues)).readOnly();
    }
}
