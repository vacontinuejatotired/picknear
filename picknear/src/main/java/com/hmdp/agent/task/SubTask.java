package com.hmdp.agent.task;

import lombok.Builder;
import lombok.Data;

import java.util.List;
import java.util.Map;

/**
 * 子任务数据模型。
 * <p>
 * 由 {@link TaskPlanner#decompose} 生成，{@link TaskExecutor} 执行。
 * 支持 TOOL_CALL（调用 @Tool）和 LLM_REASON（调 ChatClient 聚合）两种类型。
 * </p>
 */
@Data
@Builder
public class SubTask {

    /** 子任务全局唯一 ID */
    private String id;

    /** 可读描述（如"执行工具: queryBlog"、"基于以上数据生成结论"） */
    private String description;

    /** 任务类型 */
    private TaskType type;

    /** TOOL_CALL 时的工具名称，对应 GuardedToolCallback.getName() */
    private String toolName;

    /** TOOL_CALL 时的参数（可为空，由 ToolCallback 自行兜底） */
    private Map<String, Object> params;

    /** 依赖的子任务 ID 列表（全部结束后本任务才可执行） */
    private List<String> dependsOn;

    /** 当前状态 */
    private SubTaskStatus status;

    /** 执行结果（字符串形式） */
    private Object result;

    /** 已重试次数 */
    private int retryCount;
}
