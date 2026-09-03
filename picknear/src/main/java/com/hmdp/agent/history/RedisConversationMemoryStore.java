package com.hmdp.agent.history;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hmdp.agent.config.ContextCompressionProperties;
import com.hmdp.agent.model.Mem;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Optional;

/**
 * 会话记忆视图 Redis 适配器 — 单 key 原子 JSON 读写 + 滑动续期。
 * <p>
 * StringRedisTemplate + 项目 ObjectMapper（值即 JSON 串，与全仓惯例一致，
 * 避免 GenericJackson 序列化器的类型头噪音）。
 * </p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RedisConversationMemoryStore implements ConversationMemoryStore {

    private final StringRedisTemplate stringRedisTemplate;
    private final ObjectMapper objectMapper;
    private final ConversationMemoryKeyFactory keyFactory;
    private final ContextCompressionProperties properties;

    @Override
    public Optional<Mem> read(String conversationId, Long userId) {
        String key = keyFactory.memKey(conversationId);
        try {
            String json = stringRedisTemplate.opsForValue().get(key);
            if (json == null) {
                return Optional.empty();
            }
            return Optional.of(objectMapper.readValue(json, Mem.class));
        } catch (Exception e) {
            log.warn("读取会话记忆视图失败（fail-open），conversationId={}", conversationId, e);
            return Optional.empty();
        }
    }

    @Override
    public void update(String conversationId, Long userId, Mem mem) {
        String key = keyFactory.memKey(conversationId);
        try {
            stringRedisTemplate.opsForValue().set(key, objectMapper.writeValueAsString(mem), ttl());
        } catch (Exception e) {
            log.warn("写入会话记忆视图失败，conversationId={}", conversationId, e);
        }
    }

    @Override
    public void delete(String conversationId) {
        stringRedisTemplate.delete(keyFactory.memKey(conversationId));
    }

    private Duration ttl() {
        return properties.getRedis().getMemTtl();
    }
}