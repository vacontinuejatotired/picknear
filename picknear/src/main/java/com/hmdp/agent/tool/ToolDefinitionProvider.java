package com.hmdp.agent.tool;

import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

/**
 * 工具描述覆盖点：把 {@link ToolCallback} 的原始 {@link ToolDefinition}
 * （源自 {@code @Tool}/{@code @ToolParam} 注解）解析为外置后的定义。
 * <p>
 * 唯一实现由 {@code GuardedToolCallback.getToolDefinition()} 调用，
 * 覆盖结果自动传播到 LLM 函数 schema 与 TaskPlanner 的工具列表。
 * </p>
 */
@FunctionalInterface
public interface ToolDefinitionProvider {

    /** 返回覆盖后的定义；外置链路失败时返回 delegate 原始定义（回退注解） */
    ToolDefinition resolve(ToolCallback delegate);
}
