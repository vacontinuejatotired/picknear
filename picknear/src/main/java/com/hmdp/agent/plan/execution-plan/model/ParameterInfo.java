package com.hmdp.agent.plan.executionPlan.model;

import lombok.Builder;
import lombok.Data;

/**
 * 参数信息
 *
 * <p>描述工具方法的参数信息，包括参数名、类型、是否来自依赖工具等。</p>
 */
@Data
@Builder
public class ParameterInfo {

    /** 参数名 */
    private String name;

    /** 参数类型 */
    private Class<?> type;

    /** 是否来自依赖工具 */
    private boolean fromDependency;

    /** 依赖的工具名称（fromDependency=true 时） */
    private String dependencyToolName;

    /** 依赖工具的返回类型（用于类型校验） */
    private Class<?> dependencyReturnType;

    /** 是否显式指定来源（@FromTool） */
    private boolean explicitSource;

    /** 是否有歧义（多个工具返回相同类型，需要 @FromTool 指定） */
    private boolean ambiguous;
}
