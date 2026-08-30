package com.hmdp.agent.guard.policy;

import com.hmdp.agent.config.PromptGuardProperties;
import com.hmdp.agent.config.PromptGuardProperties.PatternRule;
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
 * PatternMatchPolicy — 正则匹配策略测试。
 */
@ExtendWith(MockitoExtension.class)
class PatternMatchPolicyTest {

    @Mock
    private PromptGuardProperties properties;

    private PatternMatchPolicy policy;

    @BeforeEach
    void setUp() {
        policy = new PatternMatchPolicy(properties);
    }

    @Test
    void should_block_when_tool_matches_block_pattern() {
        PatternRule rule = new PatternRule();
        rule.setToolName(".*[Dd]elete.*");
        when(properties.getBlockPatterns()).thenReturn(List.of(rule));

        ToolInvocationContext ctx = ToolInvocationContext.builder().toolName("deleteBlog").build();

        Vote vote = policy.vote(ctx);

        assertThat(vote).as("匹配 blockPatterns 应拦截").isEqualTo(Vote.BLOCK);
    }

    @Test
    void should_confirm_when_arg_matches_confirm_pattern() {
        PatternRule rule = new PatternRule();
        rule.setArguments(".*confirm.*");
        when(properties.getConfirmPatterns()).thenReturn(List.of(rule));

        ToolInvocationContext ctx = ToolInvocationContext.builder()
                .toolName("testTool").arguments("{\"confirm\":true}").build();

        Vote vote = policy.vote(ctx);

        assertThat(vote).as("匹配 confirmPatterns 参数应确认").isEqualTo(Vote.CONFIRM);
    }

    @Test
    void should_abstain_when_no_match() {
        when(properties.getBlockPatterns()).thenReturn(List.of());
        when(properties.getConfirmPatterns()).thenReturn(List.of());

        ToolInvocationContext ctx = ToolInvocationContext.builder().toolName("queryWeather").build();

        Vote vote = policy.vote(ctx);

        assertThat(vote).as("无匹配时应弃权").isEqualTo(Vote.ABSTAIN);
    }
}
