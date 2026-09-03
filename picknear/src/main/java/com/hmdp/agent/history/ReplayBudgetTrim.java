package com.hmdp.agent.history;

import com.hmdp.agent.entity.AgentMessage;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 历史回放字符预算裁剪 — 从最旧丢弃，直到累计字符不超预算。
 * <p>
 * 单职责：防止超长 message 把每轮输入 token 撑爆（P1 入口止损；
 * P2 异步压缩上线后本处退化为纯兜底）。入参须为升序（老→新）：
 * 优先保留最近，超预算从最旧开始丢；最新一条恒保留（即便单条超预算）；
 * maxChars ≤ 0 表示不限制。
 * </p>
 */
@Component
public class ReplayBudgetTrim {

    /**
     * @param ascending 升序历史（老→新），可能返回其 subList 视图
     * @param maxChars  累计字符预算（≤0 不限制）
     */
    public List<AgentMessage> trimToBudget(List<AgentMessage> ascending, int maxChars) {
        if (ascending.isEmpty() || maxChars <= 0) {
            return ascending;
        }
        int keepFrom = ascending.size();
        long acc = 0;
        for (int i = ascending.size() - 1; i >= 0; i--) {
            int len = contentLength(ascending.get(i));
            // 最新一条恒保留（可能单条超预算），更旧的按累计不超预算逐步保留
            if (i == ascending.size() - 1 || acc + len <= maxChars) {
                keepFrom = i;
                acc += len;
            } else {
                break;
            }
        }
        return ascending.subList(keepFrom, ascending.size());
    }

    private int contentLength(AgentMessage m) {
        String content = m.getContent();
        return content == null ? 0 : content.length();
    }
}