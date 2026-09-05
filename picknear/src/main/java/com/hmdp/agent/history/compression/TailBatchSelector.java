package com.hmdp.agent.history.compression;

import com.hmdp.agent.config.ContextCompressionProperties;
import com.hmdp.agent.entity.AgentMessage;
import com.hmdp.agent.history.pressure.TokenEstimator;
import com.hmdp.agent.model.Mem;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 尾窗批次选择器 — 最旧优先、成对切批、token 预算封顶。
 * <ul>
 *   <li>保留最近 {@code keepRecentTurns} 轮完整消息不压（安全尾窗）；</li>
 *   <li>触发：可压消息数足够且估算 token ≥ 阈值 或 消息数越界（防漏检）；</li>
 *   <li>每批最多 {@code batchTurns} 轮、估算不超 {@code summary.maxTokens}（至少 1 对）。</li>
 * </ul>
 */
@Component
@RequiredArgsConstructor
public class TailBatchSelector implements BatchSelector {

    private final TokenEstimator tokenEstimator;

    @Override
    public Optional<ConversationBatch> select(Mem mem, List<AgentMessage> pendingAsc,
                                              int keepRecentTurns, ContextCompressionProperties properties) {
        if (pendingAsc.isEmpty()) {
            return Optional.empty();
        }
        int keepRows = 2 * keepRecentTurns;
        int compressible = pendingAsc.size() - keepRows;
        if (compressible <= 0) {
            return Optional.empty();
        }
        int target = Math.min(2 * properties.getBatchTurns(), compressible);
        if (target <= 0) {
            return Optional.empty();
        }
        // 压力触发：最近 keep 之前确实有可压 && （估算超阈值 或 消息越界）
        if (!triggered(pendingAsc, properties)) {
            return Optional.empty();
        }

        // 最旧优先成对取，按 token 预算逐对裁剪（保证至少 1 对）
        List<AgentMessage> kept = new ArrayList<>();
        int estimate = 0;
        int maxToken = properties.getSummary().getMaxTokens();
        for (int i = 0; i + 1 < target; i += 2) {
            AgentMessage a = pendingAsc.get(i);
            AgentMessage b = pendingAsc.get(i + 1);
            int add = tokenEstimator.estimate(blank(a.getContent())) + tokenEstimator.estimate(blank(b.getContent()));
            if (!kept.isEmpty() && estimate + add > maxToken) {
                break;
            }
            kept.add(a);
            kept.add(b);
            estimate += add;
        }
        if (kept.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(new ConversationBatch(kept.get(kept.size() - 1).getId(), kept));
    }

    private boolean triggered(List<AgentMessage> pendingAsc, ContextCompressionProperties properties) {
        int estimate = 0;
        for (AgentMessage m : pendingAsc) {
            estimate += tokenEstimator.estimate(blank(m.getContent()));
        }
        return estimate >= properties.getTrigger().getTokenThreshold()
                || pendingAsc.size() >= properties.getTrigger().getMessageCount();
    }

    private static String blank(String s) {
        return s == null ? "" : s;
    }
}