package com.hmdp.agent.execution.loop;

import lombok.Builder;
import lombok.Data;

/**
 * 工具结果条目
 *
 * <p>封装工具执行结果和类型信息，用于跨层传递结果。</p>
 */
@Data
@Builder
public class ToolResultEntry {

    /** 工具名称（用于区分相同类型的不同工具结果） */
    private final String toolName;

    /** 执行结果（失败时为 null） */
    private final Object result;

    /** 结果类型 */
    private final Class<?> type;

    /**
     * 获取类型化结果
     */
    @SuppressWarnings("unchecked")
    public <T> T getTypedResult() {
        if (result == null) return null;
        return (T) type.cast(result);
    }
}
