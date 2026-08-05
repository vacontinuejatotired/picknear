package com.hmdp.agent.subagent.model;

import com.hmdp.agent.config.SubTaskProperties;
import com.hmdp.agent.subagent.callback.SubAgentProgressCallback;
import lombok.Builder;
import lombok.Data;

/**
 * 子 Agent 执行上下文。
 * <p>
 * 聚合 SubTaskPlan、进度回调、配置、计时，避免 execute() 参数膨胀。
 * </p>
 */
@Data
@Builder
public class SubTaskExecution {

    /** 执行计划 */
    private SubTaskPlan plan;

    /** 进度回调（可为 null，不阻断执行） */
    private SubAgentProgressCallback callback;

    /** 子 Agent 配置 */
    private SubTaskProperties properties;

    /** 开始执行的时间戳（System.currentTimeMillis） */
    private long startTimeMs;

    /**
     * 是否已超过总超时。
     */
    public boolean isTotalTimeout() {
        return System.currentTimeMillis() - startTimeMs
                > properties.getTotalTimeout().toMillis();
    }
}
