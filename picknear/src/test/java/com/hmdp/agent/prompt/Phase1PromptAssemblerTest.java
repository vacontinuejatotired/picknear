package com.hmdp.agent.prompt;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase1PromptAssembler — 对话 Prompt 组装器单测（消息顺序与意图）。
 */
@ExtendWith(MockitoExtension.class)
class Phase1PromptAssemblerTest {

    private final Phase1PromptAssembler assembler = new Phase1PromptAssembler();

    @Test
    void should_assemble_history_after_system() {
        List<Message> base = assembler.assembleBase("SYSTEM", List.of(
                new UserMessage("历史-用户"),
                new AssistantMessage("历史-助手")));

        assertThat(base).as("底座 = [System, ...history]").hasSize(3);
        assertThat(base.get(0)).as("首条应为 System").isInstanceOf(SystemMessage.class);
        assertThat(((SystemMessage) base.get(0)).getText()).as("System 内容正确").isEqualTo("SYSTEM");
        assertThat(base.get(1)).as("第 2 条应为历史 User").isInstanceOf(UserMessage.class);
        assertThat(base.get(2)).as("第 3 条应为历史 Assistant").isInstanceOf(AssistantMessage.class);
    }

    @Test
    void should_assemble_system_only_when_no_history() {
        List<Message> base = assembler.assembleBase("SYSTEM", List.of());

        assertThat(base).as("无历史时仅 System").hasSize(1);
        assertThat(base.get(0)).as("仍为 SystemMessage").isInstanceOf(SystemMessage.class);
    }

    @Test
    void should_append_current_user_after_base() {
        List<Message> base = assembler.assembleBase("SYSTEM", List.of(new UserMessage("历史")));

        Prompt prompt = assembler.withCurrentUser(base, "当前问题");
        List<Message> messages = prompt.getInstructions();

        assertThat(messages).as("当前 User 追加在最后").hasSize(3);
        assertThat(((SystemMessage) messages.get(0)).getText()).as("System 保持在最前").isEqualTo("SYSTEM");
        assertThat(messages.get(2)).as("末条应为当前 User").isInstanceOf(UserMessage.class);
        assertThat(((UserMessage) messages.get(2)).getText()).as("当前内容正确").isEqualTo("当前问题");
    }

    @Test
    void should_not_mutate_base_when_building_prompt() {
        List<Message> base = assembler.assembleBase("SYSTEM", List.of(new UserMessage("历史")));

        assembler.withCurrentUser(base, "Q1");

        assertThat(base).as("底座不应被追加污染").hasSize(2);
    }
}