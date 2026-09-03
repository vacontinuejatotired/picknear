package com.hmdp.agent.history.compression;

import com.hmdp.agent.history.ConversationMemoryKeyFactory;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * 压缩待补标记（dirty）— 写脏标记给 sweeper 定向自愈。
 * 置位场景：队列拒绝（AbortPolicy）、锁未获取、压缩失败（不推进游标）。值存 userId 供 sweeper 投递。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DirtyMarker {

    private static final Duration DIRTY_TTL = Duration.ofHours(24);

    private final StringRedisTemplate stringRedisTemplate;
    private final ConversationMemoryKeyFactory keyFactory;

    public void mark(String conversationId, Long userId) {
        try {
            stringRedisTemplate.opsForValue()
                    .set(keyFactory.dirtyKey(conversationId), String.valueOf(userId), DIRTY_TTL);
        } catch (Exception e) {
            log.debug("写入 dirty 标记失败 conversationId={}（仅兜底，不致命）", conversationId, e);
        }
    }

    public Long readAndClear(String conversationId) {
        try {
            String key = keyFactory.dirtyKey(conversationId);
            String value = stringRedisTemplate.opsForValue().get(key);
            stringRedisTemplate.delete(key);
            return value == null ? null : Long.valueOf(value);
        } catch (Exception e) {
            log.debug("读取/清除 dirty 标记失败 conversationId={}", conversationId, e);
            return null;
        }
    }
}