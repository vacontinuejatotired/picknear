package com.hmdp.agent.guard.policy;

import com.hmdp.agent.config.PromptGuardProperties;
import com.hmdp.agent.guard.model.ToolInvocationContext;
import com.hmdp.agent.guard.model.Vote;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/**
 * RateLimitPolicy — 频率限制策略测试。
 */
@ExtendWith(MockitoExtension.class)
class RateLimitPolicyTest {

    @Mock
    private StringRedisTemplate stringRedisTemplate;

    @Mock
    private ValueOperations<String, String> valueOps;

    @Mock
    private PromptGuardProperties properties;

    private RateLimitPolicy policy;

    @BeforeEach
    void setUp() {
        policy = new RateLimitPolicy(stringRedisTemplate, properties);
        lenient().when(stringRedisTemplate.opsForValue()).thenReturn(valueOps);
    }

    @Test
    void should_block_when_over_limit() {
        PromptGuardProperties.RateLimit config = new PromptGuardProperties.RateLimit();
        config.setMaxPerSession(30);
        when(properties.getRateLimit()).thenReturn(config);
        when(valueOps.increment(any())).thenReturn(31L);

        ToolInvocationContext ctx = ToolInvocationContext.builder()
                .conversationId("conv-1").build();

        Vote vote = policy.vote(ctx);

        assertThat(vote).as("超过限流应拦截").isEqualTo(Vote.BLOCK);
    }

    @Test
    void should_abstain_when_under_limit() {
        PromptGuardProperties.RateLimit config = new PromptGuardProperties.RateLimit();
        config.setMaxPerSession(30);
        when(properties.getRateLimit()).thenReturn(config);
        when(valueOps.increment(any())).thenReturn(15L);

        ToolInvocationContext ctx = ToolInvocationContext.builder()
                .conversationId("conv-1").build();

        Vote vote = policy.vote(ctx);

        assertThat(vote).as("未超限应弃权（不走 BLOCK 路径）").isEqualTo(Vote.ABSTAIN);
    }

    @Test
    void should_abstain_when_redis_throws() {
        PromptGuardProperties.RateLimit config = new PromptGuardProperties.RateLimit();
        config.setMaxPerSession(30);
        when(properties.getRateLimit()).thenReturn(config);
        when(valueOps.increment(any())).thenThrow(new RuntimeException("Redis 连接超时"));

        ToolInvocationContext ctx = ToolInvocationContext.builder()
                .conversationId("conv-1").build();

        // 不应抛异常
        Vote vote = policy.vote(ctx);

        assertThat(vote).as("Redis 异常应降级弃权").isEqualTo(Vote.ABSTAIN);
    }

    @Test
    void should_abstain_when_no_conversation_id() {
        PromptGuardProperties.RateLimit config = new PromptGuardProperties.RateLimit();
        config.setMaxPerSession(30);
        when(properties.getRateLimit()).thenReturn(config);

        ToolInvocationContext ctx = ToolInvocationContext.builder().build();

        Vote vote = policy.vote(ctx);

        assertThat(vote).as("无会话 ID 应弃权").isEqualTo(Vote.ABSTAIN);
    }

    @Test
    void should_abstain_when_rate_limit_disabled() {
        PromptGuardProperties.RateLimit config = new PromptGuardProperties.RateLimit();
        config.setMaxPerSession(0);
        when(properties.getRateLimit()).thenReturn(config);

        ToolInvocationContext ctx = ToolInvocationContext.builder()
                .conversationId("conv-1").build();

        Vote vote = policy.vote(ctx);

        assertThat(vote).as("maxPerSession=0 时应弃权").isEqualTo(Vote.ABSTAIN);
    }
}
