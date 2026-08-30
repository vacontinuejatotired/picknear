package com.hmdp.agent.plan.executionPlan.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 参数来源注解（可选，用于显式指定参数来源）
 *
 * <p>当类型推断不明确时（如多个工具返回相同类型），使用此注解明确指定参数来源。</p>
 *
 * <p>参数匹配优先级：</p>
 * <ol>
 *   <li>{@code @FromTool} 注解：显式指定来源工具（最高优先级）</li>
 *   <li>类型匹配：参数类型是某个依赖工具的返回类型</li>
 *   <li>参数名匹配：参数名与依赖工具名相同</li>
 *   <li>Agent 参数：基本类型默认从 LLM 规划中获取</li>
 * </ol>
 */
@Target(ElementType.PARAMETER)
@Retention(RetentionPolicy.RUNTIME)
public @interface FromTool {

    /**
     * 来源工具名称
     *
     * <p>必须与 {@code @DependsOn} 中声明的工具名称一致。</p>
     */
    String value();
}
