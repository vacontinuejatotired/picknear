package com.hmdp.agent.history.compression;

import com.hmdp.agent.config.ContextCompressionProperties;
import com.hmdp.agent.entity.AgentMessage;
import com.hmdp.agent.model.Mem;

import java.util.List;
import java.util.Optional;

/**
 * 待压批次选择端口（策略接口，可替换实现）。
 */
public interface BatchSelector {

    /**
     * 从待压消息中切一批最旧未压消息。
     * 触发条件（不足则不压）：扣除保留尾窗（{@code keepRecentTurns} 完整轮）后仍有可压、且满足压力触发（token 阈值 / 消息数）。
     *
     * @param mem             当前记忆视图（含游标与运行摘要）
     * @param pendingAsc      游标之后待压消息（最旧优先升序）
     * @param keepRecentTurns 压缩尾窗 = 回放窗口（由 {@code agent.replay.keep-recent-turns} 统一提供，单一事实源）
     */
    Optional<ConversationBatch> select(Mem mem, List<AgentMessage> pendingAsc,
                                       int keepRecentTurns, ContextCompressionProperties properties);
}