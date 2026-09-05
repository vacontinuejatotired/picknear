package com.hmdp.agent.subagent.loop;

import com.hmdp.agent.config.SubTaskProperties;
import com.hmdp.agent.observability.api.AgentSpan;
import com.hmdp.agent.prompt.PromptService;
import com.hmdp.agent.execution.model.ExecutionInput;
import com.hmdp.agent.subagent.callback.SubAgentProgressCallback;
import org.springframework.ai.tool.ToolCallback;

import java.util.List;
import java.util.Map;

/**
 * 工具循环执行上下文（策略 execute 的输入）。
 *
 * @param callbacks    本轮可用工具（Guard 包装后，已按 plan.tasks 过滤）
 * @param systemText   系统提示词（已渲染，含 {{toolCallRule}}）
 * @param initialPrompt 首轮 user 消息（已渲染的执行 prompt，重试时可能带错误注入）
 * @param plan         子任务计划
 * @param promptService 执行 prompt 渲染器（每轮重渲染用）
 * @param toolContext  ToolContext（userId / conversationId）
 * @param props        执行配置（maxToolRounds/compressLength/maxTotalCalls/parallel*）
 * @param subagentSpan 观测：subagent span（工具执行状态记录用，可为 null）
 * @param callback     进度回调（per 请求新建，携带 SseEmitter；工具状态事件经它推送，可为 null）
 */
public record ToolLoopContext(
        List<ToolCallback> callbacks,
        String systemText,
        String initialPrompt,
        ExecutionInput plan,
        PromptService promptService,
        Map<String, Object> toolContext,
        SubTaskProperties props,
        AgentSpan subagentSpan,
        SubAgentProgressCallback callback) {
}
