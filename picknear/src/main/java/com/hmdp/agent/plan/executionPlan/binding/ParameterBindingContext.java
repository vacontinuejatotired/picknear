package com.hmdp.agent.plan.executionPlan.binding;

import com.hmdp.agent.plan.executionPlan.model.ToolMetadata;

import java.lang.reflect.Parameter;
import java.util.List;
import java.util.function.Function;

/**
 * 单个待绑定形参的解析上下文。
 *
 * @param targetToolName 下游工具名
 * @param parameter 待解析的形参（含注解与参数名）
 * @param declaredDependencies {@code @DependsOn} 声明的依赖工具列表
 * @param metadataProvider 工具名 → 工具元数据，供来源返回类型判断使用
 */
public record ParameterBindingContext(
        String targetToolName,
        Parameter parameter,
        List<String> declaredDependencies,
        Function<String, ToolMetadata> metadataProvider) {

    public ToolMetadata metadataOf(String toolName) {
        return metadataProvider.apply(toolName);
    }
}
