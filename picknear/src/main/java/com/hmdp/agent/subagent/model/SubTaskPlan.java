package com.hmdp.agent.subagent.model;

import com.hmdp.agent.task.model.SubTask;
import lombok.Builder;
import lombok.Data;

import java.util.List;
import java.util.Map;

/**
 * 子 Agent 执行计划。
 * <p>
 * 由 TaskPlanner 在每轮循环中构造，
 * 传给 SubTaskAgent.execute() 作为输入。
 * </p>
 */
@Data
@Builder
public class SubTaskPlan {

    /** 原始用户输入 */
    private String userInput;

    /** Phase 1 或上一轮的 AI 回复（当前累积回复） */
    private String currentResponse;

    /** 本轮规划的 TOOL_CALL 子任务列表 */
    private List<SubTask> tasks;

    /** 历史摘要（key=toolName, value=50字摘要） */
    private Map<String, String> historySummary;

    /** 当前用户 ID */
    private Long userId;

    /** 当前会话 ID（透传给守卫，保证审批/限流按真实会话记账） */
    private String conversationId;

    /** 当前轮次（0-based） */
    private int round;
}
