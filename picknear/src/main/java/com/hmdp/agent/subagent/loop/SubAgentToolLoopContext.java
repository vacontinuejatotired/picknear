package com.hmdp.agent.subagent.loop;

import com.hmdp.agent.config.SubTaskProperties;
import com.hmdp.agent.prompt.PromptService;
import com.hmdp.agent.subagent.model.SubTaskPlan;
import org.springframework.ai.tool.ToolCallback;

import java.util.List;
import java.util.Map;

/**
 * 工具循环执行上下文（策略 execute 的输入）。
 *
 * @param callbacks    本轮可用工具（Guard 包装后，已按 plan.tasks 过滤）
 * @param systemText   子 Agent 系统提示词（已渲染，含 {{toolCallRule}}）
 * @param initialPrompt 首轮 user 消息（已渲染的执行 prompt，重试时可能带错误注入）
 * @param plan         子任务计划
 * @param promptService 执行 prompt 渲染器（每轮重渲染用）
 * @param toolContext  ToolContext（userId / conversationId）
 * @param props        执行配置（maxToolRounds/compressLength/maxTotalCalls/parallel*）
 */
public record SubAgentToolLoopContext(
        List<ToolCallback> callbacks,
        String systemText,
        String initialPrompt,
        SubTaskPlan plan,
        PromptService promptService,
        Map<String, Object> toolContext,
        SubTaskProperties props) {
}
