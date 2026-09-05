package com.hmdp.agent.history;

import com.hmdp.agent.entity.AgentMessage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * AgentRoleReplayMessageMapper — role→Spring AI 消息 映射策略单测。
 */
@ExtendWith(MockitoExtension.class)
class AgentRoleReplayMessageMapperTest {

    @InjectMocks
    private AgentRoleReplayMessageMapper mapper;

    @Test
    void should_map_user_role_to_user_message() {
        Optional<Message> mapped = mapper.map(record("user", "记住：我叫小明"));

        assertThat(mapped).as("user role 应映射为 UserMessage").isPresent();
        assertThat(mapped.get()).as("应为 UserMessage").isInstanceOf(UserMessage.class);
        assertThat(((UserMessage) mapped.get()).getText()).as("内容不丢失").isEqualTo("记住：我叫小明");
    }

    @Test
    void should_map_assistant_role_to_assistant_message() {
        Optional<Message> mapped = mapper.map(record("assistant", "好的，已记住。"));

        assertThat(mapped).as("assistant role 应映射为 AssistantMessage").isPresent();
        assertThat(mapped.get()).as("应为 AssistantMessage").isInstanceOf(AssistantMessage.class);
        assertThat(((AssistantMessage) mapped.get()).getText()).as("内容不丢失").isEqualTo("好的，已记住。");
    }

    @Test
    void should_skip_unknown_role() {
        assertThat(mapper.map(record("system", "规划中间过程")))
                .as("未知 role 应跳过（不参与回放）").isEmpty();
    }

    @Test
    void should_skip_null_role() {
        assertThat(mapper.map(record(null, "无 role")))
                .as("null role 应跳过").isEmpty();
    }

    private static AgentMessage record(String role, String content) {
        return new AgentMessage()
                .setConversationId("conv-x")
                .setUserId(1L)
                .setRole(role)
                .setContent(content);
    }
}