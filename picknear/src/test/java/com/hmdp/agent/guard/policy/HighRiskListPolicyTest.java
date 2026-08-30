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
 * HighRiskListPolicy — 高危工具名单策略测试。
 */
@ExtendWith(MockitoExtension.class)
class HighRiskListPolicyTest {

    @Mock
    private PromptGuardProperties properties;

    private HighRiskListPolicy policy;

    @BeforeEach
    void setUp() {
        policy = new HighRiskListPolicy(properties);
    }

    @Test
    void should_block_when_tool_in_block_list() {
        when(properties.getBlockTools()).thenReturn(List.of("deleteBlog"));
        ToolInvocationContext ctx = ToolInvocationContext.builder().toolName("deleteBlog").build();

        Vote vote = policy.vote(ctx);

        assertThat(vote).as("在黑名单的工具应拦截").isEqualTo(Vote.BLOCK);
    }

    @Test
    void should_abstain_when_tool_not_in_block_list() {
        when(properties.getBlockTools()).thenReturn(List.of("deleteBlog"));
        ToolInvocationContext ctx = ToolInvocationContext.builder().toolName("queryWeather").build();

        Vote vote = policy.vote(ctx);

        assertThat(vote).as("不在黑名单的工具应弃权").isEqualTo(Vote.ABSTAIN);
    }

    @Test
    void should_abstain_when_block_list_empty() {
        when(properties.getBlockTools()).thenReturn(List.of());
        ToolInvocationContext ctx = ToolInvocationContext.builder().toolName("deleteBlog").build();

        Vote vote = policy.vote(ctx);

        assertThat(vote).as("空名单应弃权").isEqualTo(Vote.ABSTAIN);
    }
}
