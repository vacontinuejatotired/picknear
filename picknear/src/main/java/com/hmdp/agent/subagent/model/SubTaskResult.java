package com.hmdp.agent.subagent.model;

import lombok.Builder;
import lombok.Data;

import java.util.List;
import java.util.Map;

/**
 * 子 Agent 执行结果。
 * <p>
 * SubTaskAgent.execute() 的返回值。
 * summary 字段直接作为 TaskPlanner 本轮聚合结果。
 * rawResults 由子 Agent Prompt 强制附加的 JSON 数据快照解析而来。
 * </p>
 */
@Data
@Builder
public class SubTaskResult {

    /** 自然语言摘要（直接作为本轮 currentResponse，不含 JSON 快照） */
    private String summary;

    /** 工具执行结果原始数据（key=toolName, value=结果）
     *  来源：子 Agent 回复末尾 ===DATA_SNAPSHOT=== 段的 JSON 解析 */
    private Map<String, Object> rawResults;

    /** 工具执行错误信息（key=toolName, value=错误消息） */
    private Map<String, String> errors;

    /** 是否全部工具执行成功 */
    private boolean allSuccess;

    /** 实际执行的 toolName 列表（按执行顺序） */
    private List<String> executedTools;

    /** 子 Agent 调用耗时（ms） */
    private long executionTimeMs;
}
