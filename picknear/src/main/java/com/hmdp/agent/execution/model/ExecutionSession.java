package com.hmdp.agent.execution.model;

import com.hmdp.agent.config.SubTaskProperties;
import com.hmdp.agent.observability.api.AgentSpan;
import com.hmdp.agent.subagent.callback.SubAgentProgressCallback;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

/**
 * 执行会话（原 SubTaskExecution）。
 * <p>
 * 聚合 ExecutionInput、进度回调、配置、计时，避免 execute() 参数膨胀。
 * </p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ExecutionSession {

    /** 执行输入 */
    private ExecutionInput input;

    /** 进度回调（可为 null，不阻断执行） */
    private SubAgentProgressCallback callback;

    /** 子 Agent 配置 */
    private SubTaskProperties properties;

    /** 开始执行的时间戳（System.currentTimeMillis） */
    private long startTimeMs;

    /** 观测：subagent span（工具执行状态记录用，可为 null） */
    private AgentSpan subagentSpan;

    /**
     * 是否已超过总超时。
     */
    public boolean isTotalTimeout() {
        return System.currentTimeMillis() - startTimeMs
                > properties.getTotalTimeout().toMillis();
    }
}
