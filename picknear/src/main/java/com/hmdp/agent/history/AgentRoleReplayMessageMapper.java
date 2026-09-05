package com.hmdp.agent.history;

import com.hmdp.agent.entity.AgentMessage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * role → Spring AI 消息 映射实现：user→UserMessage、assistant→AssistantMessage，其余跳过。
 */
@Slf4j
@Component
public class AgentRoleReplayMessageMapper implements ReplayMessageMapper {

    @Override
    public Optional<Message> map(AgentMessage record) {
        String role = record.getRole();
        return switch (role == null ? "" : role) {
            case "user" -> Optional.of(new UserMessage(record.getContent()));
            case "assistant" -> Optional.of(new AssistantMessage(record.getContent()));
            default -> {
                log.debug("跳过未知 role 的历史消息 role={}, conversationId={}", role, record.getConversationId());
                yield Optional.empty();
            }
        };
    }
}