package com.hmdp.agent.prompt.placeholder;

import com.hmdp.agent.context.AgentContext;

import java.util.Optional;

/**
 * 占位符解析器接口。
 * <p>
 * 每个实现类负责一个占位符的解析逻辑，通过 {@link PlaceholderResolverRegistry} 注册和管理。
 * </p>
 *
 * <h3>使用方式</h3>
 * <ul>
 *   <li>内置占位符：创建 {@code @Component} 实现类，Spring 自动注册</li>
 *   <li>快速创建：使用 {@link PlaceholderResolvers#of(String, java.util.function.Function)}</li>
 * </ul>
 *
 * <h3>约定</h3>
 * <ul>
 *   <li>{@link #key()} 返回不含 {@code {{}} 的占位符名称，如 {@code "userId"}</li>
 *   <li>{@link #resolve(AgentContext)} 返回 {@link Optional#empty()} 表示"该占位符无值"</li>
 *   <li>返回 {@link Optional#of(String)} 表示解析成功（值可能为空串）</li>
 * </ul>
 */
public interface PlaceholderResolver {

    /**
     * 占位符 key（不含 {@code {{}}）。
     *
     * @return 占位符名称，如 {@code "userId"}、{@code "currentTime"}
     */
    String key();

    /**
     * 从上下文解析占位符值。
     *
     * @param context Agent 上下文
     * @return 解析结果：{@link Optional#empty()} 表示无值；{@link Optional#of(String)} 表示成功
     */
    Optional<String> resolve(AgentContext context);
}
