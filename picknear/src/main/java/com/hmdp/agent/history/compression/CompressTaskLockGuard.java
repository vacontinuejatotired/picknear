package com.hmdp.agent.history.compression;

import com.hmdp.agent.config.CompressionExecutorProperties;
import com.hmdp.agent.history.ConversationMemoryKeyFactory;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;

import java.util.concurrent.TimeUnit;

/**
 * 会话压缩互斥锁守卫 — 包装压缩任务，单会话单写者。
 * 拿不到锁/超时 → 置 dirty 丢弃本次（幂等：checkpoint 增量保证下次追平，不丢信息）；绝不阻塞请求链。
 */
@Slf4j
@RequiredArgsConstructor
public class CompressTaskLockGuard implements Runnable {

    private final RedissonClient redisson;
    private final ConversationMemoryKeyFactory keyFactory;
    private final CompressionExecutorProperties properties;
    private final DirtyMarker dirtyMarker;
    private final String conversationId;
    private final Long userId;
    private final Runnable delegate;

    @Override
    public void run() {
        RLock lock = redisson.getLock(keyFactory.lockKey(conversationId));
        boolean acquired;
        try {
            acquired = lock.tryLock(0, properties.getLockLease().toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            dirtyMarker.mark(conversationId, userId);
            return;
        }
        if (!acquired) {
            log.debug("会话压缩锁被占用，丢弃本次 conversationId={}（留 dirty 待追平）", conversationId);
            dirtyMarker.mark(conversationId, userId);
            return;
        }
        try {
            delegate.run();
        } finally {
            try {
                lock.unlock();
            } catch (IllegalMonitorStateException ignored) {
                // lease 到点已由 Redisson 释放
            }
        }
    }
}