package com.hmdp.agent.history;

import com.hmdp.agent.entity.AgentMessage;
import org.springframework.ai.chat.messages.Message;

import java.util.Optional;

/**
 * 持久化消息 → Spring AI 消息 映射策略。
 * <p>
 * 单职责：把 agent_message 的一条记录翻译成模型输入可用的消息，未知 role 返回空表跳过。
 * 压缩子系统新增摘要消息类型时，可插新实现而不改动既有角色映射。
 * </p>
 */
public interface ReplayMessageMapper {

    /** @return 映射结果；空表表示该条不参与回放 */
    Optional<Message> map(AgentMessage record);
}