package com.hmdp.agent.plan.executionPlan.binding;

import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * 一次执行计划的参数绑定快照。
 *
 * <p>按下游工具分组，执行期直接读取，不重复做注解/反射分析。</p>
 *
 * @param bindingsByTool 下游工具名 → 参数绑定列表
 * @param issues 本次规划发现的绑定问题
 */
public record ToolParameterBindingPlan(
        Map<String, List<ToolParameterBinding>> bindingsByTool,
        List<ParameterBindingIssue> issues) {

    public static ToolParameterBindingPlan empty() {
        return new ToolParameterBindingPlan(Map.of(), List.of());
    }

    public boolean isValid() {
        return issues.isEmpty();
    }

    public List<ToolParameterBinding> bindingsFor(String toolName) {
        return bindingsByTool.getOrDefault(toolName, List.of());
    }

    public ToolParameterBindingPlan readOnly() {
        return new ToolParameterBindingPlan(
            Collections.unmodifiableMap(bindingsByTool),
            List.copyOf(issues));
    }
}
