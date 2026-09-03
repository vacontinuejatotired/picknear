package com.hmdp.agent.history.compression;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.hmdp.agent.entity.AgentMessage;
import com.hmdp.agent.mapper.AgentMessageMapper;
import com.hmdp.agent.model.Mem;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 待压载荷探测 — 取 [checkpoint + 1, tail] 全量（最旧优先，分批推进不挖洞）。
 */
@Component
@RequiredArgsConstructor
public class LoadProber {

    private static final int MAX_LOAD_ROWS = 2000;

    private final AgentMessageMapper messageMapper;

    /** @param mem 可能含压缩游标（uptoId>0 时只取游标之后的增量）；升序返回 */
    public List<AgentMessage> loadPending(Long userId, String conversationId, Mem mem) {
        LambdaQueryWrapper<AgentMessage> wrapper = new LambdaQueryWrapper<AgentMessage>()
                .eq(AgentMessage::getConversationId, conversationId)
                .eq(AgentMessage::getUserId, userId);
        if (mem != null && mem.uptoId() > 0) {
            wrapper.gt(AgentMessage::getId, mem.uptoId());
        }
        return messageMapper.selectList(wrapper
                .orderByAsc(AgentMessage::getId)
                .last("LIMIT " + MAX_LOAD_ROWS));
    }
}