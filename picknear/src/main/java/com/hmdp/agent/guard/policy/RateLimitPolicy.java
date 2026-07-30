package com.hmdp.agent.guard.policy;

import com.hmdp.agent.config.PromptGuardProperties;
import com.hmdp.agent.guard.ToolGuardPolicy;
import com.hmdp.agent.guard.ToolInvocationContext;
import com.hmdp.agent.guard.Vote;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * 频率限制策略
 * <p>
 * 基于 Redis 计数器统计每个会话中的工具调用次数，
 * 超过阈值则返回 {@link Vote#BLOCK}，防止 AI 进入失控循环。
 * </p>
 * <p>
 * 无任何业务 Service 依赖，仅操作 Redis 字符串计数器。
 * </p>
 *
 * <pre>{@code
 * hmdp:
 *   prompt-guard:
 *     rate-limit:
 *       max-per-session: 30
 *       window-seconds: 60
 * }</pre>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RateLimitPolicy implements ToolGuardPolicy {

    private final StringRedisTemplate stringRedisTemplate;
    private final PromptGuardProperties properties;

    private static final String KEY_PREFIX = "guard:rate:";

    @Override
    public Vote vote(ToolInvocationContext context) {
        PromptGuardProperties.RateLimit config = properties.getRateLimit();
        if (config == null || config.getMaxPerSession() <= 0) {
            return Vote.ABSTAIN;
        }

        String conversationId = context.getConversationId();
        if (conversationId == null || conversationId.isBlank()) {
            return Vote.ABSTAIN; // 无会话 ID 无法限流
        }

        String key = KEY_PREFIX + conversationId;
        try {
            Long count = stringRedisTemplate.opsForValue().increment(key);

            if (count == null) {
                return Vote.ABSTAIN;
            }

            // 首次创建时设置过期时间
            if (count == 1) {
                stringRedisTemplate.expire(key, Duration.ofSeconds(config.getWindowSeconds()));
            }

            if (count > config.getMaxPerSession()) {
                log.warn("频率限制触发: conversation={}, count={}, max={}",
                        conversationId, count, config.getMaxPerSession());
                return Vote.BLOCK;
            }
        } catch (Exception e) {
            log.warn("Redis 频率限制异常，已放行: {}", e.toString());
            // Redis 不可用时降级，不阻塞工具调用
        }

        return Vote.ABSTAIN;
    }
}
