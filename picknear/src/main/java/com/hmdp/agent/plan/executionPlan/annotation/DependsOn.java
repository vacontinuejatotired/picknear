package com.hmdp.agent.plan.executionPlan.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 工具依赖声明注解
 *
 * <p>声明当前工具依赖的其他工具，DAG 执行器会根据依赖关系进行拓扑排序分层：</p>
 * <ul>
 *   <li>无依赖的工具：并行执行</li>
 *   <li>有依赖的工具：等依赖完成后串行执行</li>
 * </ul>
 *
 * <p>示例：</p>
 * <pre>
 * @Tool
 * @DependsOn(toolName = {"queryWeather", "queryBlog"})
 * public ItineraryResult generateItinerary(String city) {
 *     // queryWeather 和 queryBlog 会先并行执行
 *     // 本工具在它们完成后执行
 * }
 * </pre>
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface DependsOn {

    /**
     * 依赖的工具名称列表
     *
     * <p>工具名称对应 {@code @Tool} 注解的 name 属性或方法名。</p>
     */
    String[] toolName();
}
