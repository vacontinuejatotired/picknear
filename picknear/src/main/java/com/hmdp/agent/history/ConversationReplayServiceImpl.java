package com.hmdp.agent.history;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.hmdp.agent.config.ReplayProperties;
import com.hmdp.agent.entity.AgentMessage;
import com.hmdp.agent.mapper.AgentMessageMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.Message;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 多轮记忆回放契约服务实现 — 瘦编排。
 * <p>
 * 职责：enabled 门 → 尾部窗口查询（最新在前 LIMIT 后 reverse 升序）→ 委托
 * {@link ReplayMessageMapper} 逐条映射 → 返回最近 N 轮历史。业务细节全在依赖小类。
 * fail-open：DB 异常只记日志返回空，绝不阻断对话（压缩子系统复用本读取点同样受益）。
 * </p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ConversationReplayServiceImpl implements ConversationReplayService {

    private final AgentMessageMapper messageMapper;
    private final ReplayProperties replayProperties;
    private final ReplayMessageMapper replayMessageMapper;

    @Override
    public List<Message> recentMessages(Long userId, String conversationId, int windowTurns) {
        if (!replayProperties.isEnabled() || windowTurns <= 0) {
            return List.of();
        }
        try {
            // 尾部 2×windowTurns 条（user+assistant 各一条/轮），最新在前
            // 拷贝至可变列表再 reverse：不依赖 mapper 返回类型可变性
            List<AgentMessage> rows = new ArrayList<>(messageMapper.selectList(new LambdaQueryWrapper<AgentMessage>()
                    .eq(AgentMessage::getConversationId, conversationId)
                    .eq(AgentMessage::getUserId, userId)
                    .orderByDesc(AgentMessage::getId)
                    .last("LIMIT " + (2 * windowTurns))));
            Collections.reverse(rows); // 转升序，与对话顺序一致
            List<Message> history = new ArrayList<>(rows.size());
            for (AgentMessage row : rows) {
                replayMessageMapper.map(row).ifPresent(history::add);
            }
            return history;
        } catch (Exception e) {
            log.warn("读取多轮记忆历史失败，fail-open 返回空 conversationId={}, userId={}",
                    conversationId, userId, e);
            return List.of();
        }
    }
}