package com.hmdp.agent.execution.model;

import lombok.Builder;
import lombok.Data;

import java.util.List;
import java.util.Map;

/**
 * 执行输出（原 SubTaskResult）。
 * <p>
 * ToolExecutionFacade.execute() 的返回值。
 * summary 字段直接作为编排层本轮聚合结果。
 * </p>
 */
@Data
@Builder
public class ExecutionOutput {

    /** 自然语言摘要（直接作为本轮 currentResponse，不含 JSON 快照） */
    private String summary;

    /** 工具执行结果原始数据（key=toolName, value=结果） */
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
