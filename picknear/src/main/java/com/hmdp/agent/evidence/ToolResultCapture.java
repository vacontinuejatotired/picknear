package com.hmdp.agent.evidence;

import java.util.List;

/**
 * 轮级工具真值证据累加器（反编造 L0，端口）。
 * <p>
 * 职责：在工具执行点 {@link #capture} 登记本轮每个工具的真实返回，供编排层执行完成后
 * {@link #snapshot} 快照。
 * </p>
 * <p>
 * 轮级隔离约定：编排层每次 executeRound 前 {@link #begin}、成功后 {@link #snapshot}。
 * 具体状态载体由实现决定；Graph 实现应使用显式执行上下文，而不是 ThreadLocal。
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
