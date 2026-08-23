package com.hmdp.agent.execution.model;

import com.hmdp.agent.plan.model.SubTask;
import lombok.Builder;
import lombok.Data;

import java.util.List;
import java.util.Map;

/**
 * 执行输入（原 SubTaskPlan）。
 * <p>
 * 由编排层在每轮循环中构造，
 * 传给 ToolExecutionFacade.execute() 作为输入。
 * </p>
 */
@Data
@Builder
public class ExecutionInput {

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
