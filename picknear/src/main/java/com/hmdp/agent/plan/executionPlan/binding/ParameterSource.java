package com.hmdp.agent.plan.executionPlan.binding;

/**
 * 下游参数来源的匹配规则。
 *
 * <p>用于说明该绑定是按哪条优先级得到的，方便日志和排障：</p>
 * <ol>
 *   <li>{@link #FROM_TOOL}：{@code @FromTool} 显式指定</li>
 *   <li>{@link #RETURN_TYPE}：返回类型唯一匹配</li>
 *   <li>{@link #PARAMETER_NAME}：参数名与依赖工具名一致</li>
 * </ol>
 */
public enum ParameterSource {
    FROM_TOOL,
    RETURN_TYPE,
    PARAMETER_NAME
}
