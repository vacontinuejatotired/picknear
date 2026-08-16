package com.hmdp.common.lock;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import jakarta.annotation.Resource;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * 分布式锁模板 — 统一锁的获取与安全释放（common 域收敛，P4-S1）
 * <p>
 * 修复审查 H-3（锁无所有者标识）：value 为随机 UUID（所有者标识），
 * 释放走 Lua（RedisUnlock.lua）校验 {@code get == value} 才 del，
 * 杜绝"A 的锁超时被 B 持有后，A 的 finally 误删 B 的锁"。
 * </p>
 * <pre>
 * // 用法一：try-with-resources
 * try (LockTemplate.LockHandle lock = lockTemplate.tryLock(key, 3, TimeUnit.SECONDS)) {
 *     // 抢锁失败（lock == null）时自行降级
 *     // 临界区
 * }
 * // 用法二：便捷模板（抢锁失败返回 null）
 * String r = lockTemplate.withLock(key, 3, TimeUnit.SECONDS, () -&gt; "done");
 * </pre>
 */
@Component
public class LockTemplate {

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource(name = "redisUnlockScript")
    private DefaultRedisScript<Long> redisUnlockScript;

    /**
     * 尝试加锁（SET NX EX + UUID 所有者标识）
     *
     * @return 成功返回锁句柄（AutoCloseable，close 时 Lua 校验释放）；失败返回 null
     */
    public LockHandle tryLock(String key, long ttl, TimeUnit unit) {
        String value = UUID.randomUUID().toString();
        Boolean ok = stringRedisTemplate.opsForValue().setIfAbsent(key, value, ttl, unit);
        return Boolean.TRUE.equals(ok) ? new LockHandle(key, value) : null;
    }

    /**
     * 便捷模板：抢锁成功执行 action，失败返回 null（语义同 tryLock 的调用方降级）
     */
    public <T> T withLock(String key, long ttl, TimeUnit unit, Supplier<T> action) {
        try (LockHandle lock = tryLock(key, ttl, unit)) {
            if (lock == null) {
                return null;
            }
            return action.get();
        }
    }

    /**
     * 锁句柄 — AutoCloseable；close() 执行 Lua 校验释放（仅所有者可释放）
     */
    public class LockHandle implements AutoCloseable {

        private final String key;
        private final String value;
        private volatile boolean closed;

        LockHandle(String key, String value) {
            this.key = key;
            this.value = value;
        }

        @Override
        public void close() {
            if (closed) {
                return;
            }
            closed = true;
            try {
                stringRedisTemplate.execute(redisUnlockScript, List.of(key), value);
            } catch (Exception e) {
                // 释放失败（如 Redis 抖动）：锁会随 TTL 过期，不阻塞业务
                // 日志由调用方决定是否记录，这里静默避免 try-with-resources 吞异常
            }
        }
    }
}
