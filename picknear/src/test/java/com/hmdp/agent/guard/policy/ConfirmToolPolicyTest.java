package com.hmdp.agent.guard.policy;

import com.hmdp.agent.config.PromptGuardProperties;
import com.hmdp.agent.guard.model.ToolInvocationContext;
import com.hmdp.agent.guard.model.Vote;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/**
 * ConfirmToolPolicy — 需确认工具名单策略测试。
 */
@ExtendWith(MockitoExtension.class)
class ConfirmToolPolicyTest {

    @Mock
    private PromptGuardProperties properties;

    private ConfirmToolPolicy policy;

    @BeforeEach
    void setUp() {
        policy = new ConfirmToolPolicy(properties);
    }

    @Test
    void should_confirm_when_tool_in_confirm_list() {
        when(properties.getConfirmTools()).thenReturn(List.of("publishTestBlog"));
        ToolInvocationContext ctx = ToolInvocationContext.builder().toolName("publishTestBlog").build();

        Vote vote = policy.vote(ctx);

        assertThat(vote).as("在确认名单的工具应返回 CONFIRM").isEqualTo(Vote.CONFIRM);
    }

    @Test
    void should_abstain_when_tool_not_in_confirm_list() {
        when(properties.getConfirmTools()).thenReturn(List.of("publishTestBlog"));
        ToolInvocationContext ctx = ToolInvocationContext.builder().toolName("queryWeather").build();

        Vote vote = policy.vote(ctx);

        assertThat(vote).as("不在确认名单应弃权").isEqualTo(Vote.ABSTAIN);
    }

    @Test
    void should_abstain_when_confirm_list_empty() {
        when(properties.getConfirmTools()).thenReturn(List.of());
        ToolInvocationContext ctx = ToolInvocationContext.builder().toolName("publishTestBlog").build();

        Vote vote = policy.vote(ctx);

        assertThat(vote).as("空名单应弃权").isEqualTo(Vote.ABSTAIN);
    }
}
