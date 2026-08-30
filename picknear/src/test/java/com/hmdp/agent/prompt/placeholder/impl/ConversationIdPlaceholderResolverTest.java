package com.hmdp.agent.prompt.placeholder.impl;

import com.hmdp.agent.context.AgentContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ConversationIdPlaceholderResolver 测试。
 */
class ConversationIdPlaceholderResolverTest {

    private ConversationIdPlaceholderResolver resolver;

    @BeforeEach
    void setUp() {
        resolver = new ConversationIdPlaceholderResolver();
    }

    @Test
    void should_return_correct_key() {
        assertThat(resolver.key()).isEqualTo("conversationId");
    }

    @Test
    void should_resolve_conversation_id() {
        // given
        AgentContext context = AgentContext.builder()
                .conversationId("conv_abc123")
                .build();

        // when
        Optional<String> result = resolver.resolve(context);

        // then
        assertThat(result).hasValue("conv_abc123");
    }

    @Test
    void should_return_empty_when_conversation_id_is_null() {
        // given
        AgentContext context = AgentContext.builder()
                .conversationId(null)
                .build();

        // when
        Optional<String> result = resolver.resolve(context);

        // then
        assertThat(result).isEmpty();
    }

    @Test
    void should_return_empty_when_conversation_id_is_empty() {
        // given
        AgentContext context = AgentContext.builder()
                .conversationId("")
                .build();

        // when
        Optional<String> result = resolver.resolve(context);

        // then
        assertThat(result).isEmpty();
    }

    @Test
    void should_return_empty_when_context_is_null() {
        // when
        Optional<String> result = resolver.resolve(null);

        // then
        assertThat(result).isEmpty();
    }
}
