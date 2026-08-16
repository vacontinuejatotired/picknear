package com.hmdp.agent.plan.model;

import com.hmdp.agent.task.model.TaskReport;
import org.springframework.ai.tool.ToolCallback;

/**
 * 规划请求上下文（PlanRouter 流水线输入）。
 */
public record PlanRequest(
        String userInput,
        String aiResponse,
        Long userId,
        ToolCallback[] toolCallbacks,
        TaskReport history) {
}
