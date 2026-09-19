package com.hmdp.agent.execution.evidence;

import com.hmdp.agent.execution.model.ToolEvidence;

import java.util.List;

/**
 * 轮级工具真值证据累加器（反编造 L0，端口）。
 * <p>
 * 职责：在工具执行点 {@link #capture} 登记本轮每个工具的真实返回，供编排层执行完成后
 * {@link #snapshot} 快照并挂到 {@code ExecutionOutput.toolEvidence}。
 * </p>
 * <p>
 * 轮级隔离约定：编排层每次 executeRound 前 {@link #begin}、成功后 {@link #snapshot}；
 * 累加器状态放在 {@code AgentContext.attributes}（已由 AgentContextPropagator 跨线程传播，
 * 并行策略子线程同样可写）。
 * </p>
 */
public interface ToolResultCapture {

    /** 开启一轮证据收集（覆盖上一轮遗留，幂等） */
    void begin();

    /** 登记一次工具真实返回（仅成功执行的工具；raw 为模型可见文本） */
    void capture(String toolName, String raw);

    /** 快照并清空本轮证据（空则返回空表） */
    List<ToolEvidence> snapshot();
}
