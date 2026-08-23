package com.hmdp.agent.prompt.placeholder.impl;

import com.hmdp.agent.context.AgentContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * UserIdPlaceholderResolver 测试。
 */
class UserIdPlaceholderResolverTest {

    private UserIdPlaceholderResolver resolver;

    @BeforeEach
    void setUp() {
        resolver = new UserIdPlaceholderResolver();
    }

    @Test
    void should_return_correct_key() {
        assertThat(resolver.key()).isEqualTo("userId");
    }

    @Test
    void should_resolve_user_id() {
        // given
        AgentContext context = AgentContext.builder()
                .userId(12345L)
                .build();

        // when
        Optional<String> result = resolver.resolve(context);

        // then
        assertThat(result).hasValue("12345");
    }

    @Test
    void should_return_empty_when_user_id_is_null() {
        // given
        AgentContext context = AgentContext.builder()
                .userId(null)
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

    @Test
    void should_resolve_large_user_id() {
        // given
        AgentContext context = AgentContext.builder()
                .userId(9999999999L)
                .build();

        // when
        Optional<String> result = resolver.resolve(context);

        // then
        assertThat(result).hasValue("9999999999");
    }
}
