package com.hmdp.agent.history.ledger;

import com.hmdp.agent.execution.model.ToolEvidence;

import java.util.List;

/**
 * 事实账本端口（反编造 L4）——只存工具真值，供跨轮回放注入【已核实事实】。
 * <p>
 * 关键约束：append 只接收 ExecutionOutput.toolEvidence（L0 捕获的工具真实返回），
 * 绝不落 assistant 编造文本，从源头避免"把谎话写进记忆"。
 * </p>
 */
public interface FactLedgerStore {

    /**
     * 把一轮工具真值证据追加为账本行。
     *
     * @param conversationId 会话 ID
     * @param evidence       本轮工具真值（可为空，空则 no-op）
     */
    void append(String conversationId, List<ToolEvidence> evidence);

    /**
     * 读取账本文本（按预算保留最新行，超预算裁最旧）。无账本返回空串。
     */
    String read(String conversationId);
}
