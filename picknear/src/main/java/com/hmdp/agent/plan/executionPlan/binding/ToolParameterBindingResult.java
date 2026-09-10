package com.hmdp.agent.plan.executionPlan.binding;

import java.util.List;

/**
 * 单个工具的绑定解析结果。
 *
 * @param bindings 可安全注入的参数绑定
 * @param issues 需要拦截的可读问题
 */
public record ToolParameterBindingResult(
        List<ToolParameterBinding> bindings,
        List<ParameterBindingIssue> issues) {

    public static ToolParameterBindingResult empty() {
        return new ToolParameterBindingResult(List.of(), List.of());
    }

    public boolean isValid() {
        return issues.isEmpty();
    }
}
