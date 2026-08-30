package com.hmdp.agent.execution.loop;

import com.hmdp.agent.execution.metrics.ToolExecutionMetrics;
import lombok.Builder;
import lombok.Data;

import java.util.List;
import java.util.Map;

/**
 * DAG 执行结果
 *
 * <p>封装一次 DAG 执行的完整结果。</p>
 */
@Data
@Builder
public class DagExecutionResult {

    /** 是否成功 */
    private boolean success;

    /** 所有工具结果 */
    private Map<String, Object> results;

    /** 失败原因（默认空 Map，避免 failed() 返回时 NPE） */
    @Builder.Default
    private Map<String, String> failedReasons = Map.of();

    /** 已执行的工具 */
    private List<String> executedTools;

    /** 失败的工具 */
    private List<String> failedTools;

    /** 执行耗时（毫秒） */
    private long duration;

    /** 错误信息 */
    private String errorMessage;

    /** 本次执行的指标列表（每次执行独立收集，不共享） */
    @Builder.Default
    private List<ToolExecutionMetrics> metrics = List.of();

    /**
     * 创建成功结果
     */
    public static DagExecutionResult success(Map<String, Object> results,
                                              List<String> executed,
                                              List<String> failed,
                                              Map<String, String> failedReasons,
                                              List<ToolExecutionMetrics> metrics,
                                              long duration) {
        return DagExecutionResult.builder()
            .success(failed.isEmpty())
            .results(results)
            .executedTools(executed)
            .failedTools(failed)
            .failedReasons(failedReasons)
            .metrics(metrics)
            .duration(duration)
            .build();
    }

    /**
     * 创建失败结果
     */
    public static DagExecutionResult failed(String errorMessage) {
        return DagExecutionResult.builder()
            .success(false)
            .errorMessage(errorMessage)
            .build();
    }

    /**
     * 判断某个工具是否执行成功
     *
     * <p>工具执行成功但返回 null（如删除操作）也算成功</p>
     */
    public boolean isSuccess(String toolName) {
        return results.containsKey(toolName) &&
               (failedReasons == null || !failedReasons.containsKey(toolName));
    }

    /**
     * 获取指定工具的结果
     */
    @SuppressWarnings("unchecked")
    public <T> T getResult(String toolName, Class<T> type) {
        Object result = results.get(toolName);
        if (result == null) return null;
        return type.cast(result);
    }
}
