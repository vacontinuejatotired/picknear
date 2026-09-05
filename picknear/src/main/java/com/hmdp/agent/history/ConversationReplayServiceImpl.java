package com.hmdp.agent.history;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.hmdp.agent.config.ReplayProperties;
import com.hmdp.agent.entity.AgentMessage;
import com.hmdp.agent.history.ledger.FactLedgerStore;
import com.hmdp.agent.mapper.AgentMessageMapper;
import com.hmdp.agent.model.Mem;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 多轮记忆回放契约服务实现 — 瘦编排。
 * <p>
 * 职责：enabled 门 → 读取会话记忆视图（可选）：
 * <ul>
 *   <li>有摘要：{@code [SystemMessage(历史摘要)] + id>uptoId 尾部完整消息}（旧轮已在摘要、DB 原值永不删，增量不重不漏）；</li>
 *   <li>无摘要：P1 纯近期完整窗口。</li>
 * </ul>
 * → {@link ReplayBudgetTrim} 对"近期完整"做字符预算裁剪 → 委托 {@link ReplayMessageMapper}
 * 逐条映射。业务细节全在依赖小类。
 * fail-open：DB/Redis 异常只记日志返回空，绝不阻断对话（压缩子系统复用本读取点同样受益）。
 * </p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ConversationReplayServiceImpl implements ConversationReplayService {

    /** 摘要作为消息注入时的固定引导语（SystemMessage 前缀） */
    private static final String SUMMARY_PREFIX = "【历史摘要】";

    /** 事实账本注入前缀（反编造 L4）——只有工具查询返回的真实结果可当事实引用 */
    private static final String FACTS_PREFIX = "【已核实事实】以下为工具查询返回的真实结果，仅下列条目可当作事实引用：\n";

    /** 回放原文前的区隔说明（反编造 L4）——历史对话仅供参考，不代表已核实事实 */
    private static final String ORIGIN_NOTE = "【历史原文】以下为历史对话原文，仅供理解上下文，不代表已核实事实；涉及数据的表述须重新核实。";

    private final AgentMessageMapper messageMapper;
    private final ReplayProperties replayProperties;
    private final ReplayMessageMapper replayMessageMapper;
    private final ReplayBudgetTrim replayBudgetTrim;
    private final ConversationMemoryStore memoryStore;
    private final FactLedgerStore ledgerStore;

    @Override
    public List<Message> recentMessages(Long userId, String conversationId, int windowTurns) {
        if (!replayProperties.isEnabled() || windowTurns <= 0) {
            return List.of();
        }
        try {
            Mem mem = memoryStore.read(conversationId, userId).orElse(null);
            boolean hasSummary = mem != null && mem.hasSummary();
            List<AgentMessage> pending = queryPending(mem, userId, conversationId, windowTurns);
            pending = replayBudgetTrim.trimToBudget(pending, replayProperties.getMaxReplayChars());
            List<Message> history = new ArrayList<>(pending.size() + 3);
            if (hasSummary) {
                history.add(new SystemMessage(SUMMARY_PREFIX + mem.summary()));
            }
            // 反编造 L4：账本非空才注入【已核实事实】+ 把原文区隔为"仅上下文非事实"
            // （无账本的普通对话不额外插注，保持历史结构向后兼容）
            String ledger = ledgerStore.read(conversationId);
            if (!ledger.isBlank()) {
                history.add(new SystemMessage(FACTS_PREFIX + ledger));
                if (!pending.isEmpty()) {
                    history.add(new SystemMessage(ORIGIN_NOTE));
                }
            }
            for (AgentMessage row : pending) {
                replayMessageMapper.map(row).ifPresent(history::add);
            }
            return history;
        } catch (Exception e) {
            log.warn("读取多轮记忆历史失败，fail-open 返回空 conversationId={}, userId={}",
                    conversationId, userId, e);
            return List.of();
        }
    }

    /** 尾部完整消息：有压缩游标则只取 uid>uptoId 的增量；无则 P1 全量尾部窗口。最新在前、LIMIT 后 reverse 升序。 */
    private List<AgentMessage> queryPending(Mem mem, Long userId, String conversationId, int windowTurns) {
        LambdaQueryWrapper<AgentMessage> wrapper = new LambdaQueryWrapper<AgentMessage>()
                .eq(AgentMessage::getConversationId, conversationId)
                .eq(AgentMessage::getUserId, userId);
        if (mem != null && mem.uptoId() > 0) {
            wrapper.gt(AgentMessage::getId, mem.uptoId());
        }
        List<AgentMessage> rows = new ArrayList<>(messageMapper.selectList(wrapper
                .orderByDesc(AgentMessage::getId)
                .last("LIMIT " + (2 * windowTurns))));
        Collections.reverse(rows); // 转升序，与对话顺序一致
        return rows;
    }
}