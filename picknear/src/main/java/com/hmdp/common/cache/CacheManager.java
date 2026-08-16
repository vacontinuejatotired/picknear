package com.hmdp.common.cache;

import cn.hutool.core.util.RandomUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.hmdp.common.lock.LockTemplate;
import com.hmdp.utils.cache.RedisData;
import com.hmdp.utils.redis.RedisConstants;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import jakarta.annotation.Resource;
import java.time.LocalDateTime;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

/**
 * 缓存管理器 — 通用单键缓存唯一入口（common 域收敛，P4-S2）
 * <p>
 * 自 CacheClient 迁出（行为等价，P4-S3 后 CacheClient 删除）：
 * <ul>
 *   <li>queryWithCache：缓存穿透防护（空值缓存 + 兜底回源）</li>
 *   <li>queryWithLogicExpire：缓存击穿防护（逻辑过期 + 异步重建，锁经 LockTemplate）</li>
 *   <li>set / setWithLogicalExpire / setWithBlankExpire / evict：写入与失效</li>
 * </ul>
 * 异步重建线程池为 Spring 托管有界池（{@code cacheRebuildExecutor}，替代静态 Executors）。
 * 用户信息批量缓存（BatchLoadCache，Hash 形态）不并入本门面——数据形态不同。
 * </p>
 */
@Slf4j
@Component
public class CacheManager {

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private LockTemplate lockTemplate;

    @Resource(name = "cacheRebuildExecutor")
    private Executor cacheRebuildExecutor;

    /** 通用单键缓存（空值兜底 + 回源） */
    public <R, ID> R queryWithCache(ID id, Class<R> clazz, String keyPrefix,
                                    Function<ID, R> dbFallBack, Long time, TimeUnit timeUnit) {
        String key = keyPrefix + id;
        String json = stringRedisTemplate.opsForValue().get(key);
        if (StrUtil.isNotBlank(json)) {
            return JSONUtil.toBean(json, clazz, false);
        }
        // 判断命中的是不是空值
        if (json != null) {
            return null;
        }
        R result = dbFallBack.apply(id);
        if (result == null) {
            stringRedisTemplate.opsForValue().set(key, "", RedisConstants.CACHE_NULL_TTL, TimeUnit.MINUTES);
            return null;
        }
        // 写入带随机 TTL（防缓存雪崩，P4 博客缓存收敛后统一语义）
        setWithJitter(key, result, time, timeUnit);
        return result;
    }

    /** 逻辑过期缓存查询（异步重建 + LockTemplate 防击穿） */
    public <R, ID> R queryWithLogicExpire(String keyPrefix, ID id, Class<R> clazz,
                                          Function<ID, R> dbFallBack,
                                          Long time, TimeUnit timeUnit) {
        String key = keyPrefix + id;
        String redisData = stringRedisTemplate.opsForValue().get(key);
        String lockKey = RedisConstants.LOCK_SHOP_KEY + id;

        // 1. 空值缓存（占位符）
        if (redisData != null && redisData.equals("")) {
            log.info("命中空值缓存，id={}", id);
            return null;
        }

        // 2. 缓存物理缺失
        if (redisData == null) {
            LockTemplate.LockHandle lock = lockTemplate.tryLock(lockKey,
                    RedisConstants.LOCK_SHOP_TTL, TimeUnit.SECONDS);
            if (lock != null) {
                try {
                    // 双检：再次检查是否被其他线程刚写入
                    R cached = doubleCheckCache(key, clazz);
                    if (cached != null) {
                        return cached;
                    }
                } finally {
                    lock.close(); // 立即释放锁
                }
                // 锁已释放，提交异步任务（任务内会重新抢锁执行重建）
                asyncRebuildCache(key, lockKey, id, dbFallBack, time, timeUnit, clazz);
            }
            // 没拿到锁或异步已提交，返回 null（降级）
            return null;
        }

        // 3. 解析缓存数据
        RedisData redisData1 = JSONUtil.toBean(redisData, RedisData.class);
        R r = JSONUtil.toBean((JSONObject) redisData1.getData(), clazz);

        // 4. 未过期，直接返回
        if (LocalDateTime.now().isBefore(redisData1.getExpireTime())) {
            return r;
        }

        // 5. 逻辑过期
        LockTemplate.LockHandle lock = lockTemplate.tryLock(lockKey,
                RedisConstants.LOCK_SHOP_TTL, TimeUnit.SECONDS);
        if (lock != null) {
            try {
                R updated = doubleCheckCache(key, clazz);
                if (updated != null) {
                    return updated; // 被其他线程更新了
                }
            } finally {
                lock.close(); // 释放锁
            }
            // 释放锁后提交异步重建
            asyncRebuildCache(key, lockKey, id, dbFallBack, time, timeUnit, clazz);
        }
        // 返回旧数据（可能已过期，但允许）
        return r;
    }

    public void set(String key, Object value, Long time, TimeUnit timeUnit) {
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(value), time, timeUnit);
    }

    /**
     * 带随机 TTL 的写入（70%~100% 区间）— 防缓存雪崩（博客/通用缓存统一语义）
     */
    public void setWithJitter(String key, Object value, Long time, TimeUnit timeUnit) {
        long jittered = RandomUtil.randomLong(time * 7 / 10, time);
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(value), jittered, timeUnit);
    }

    public void setWithLogicalExpire(String key, Object value, Long time, TimeUnit timeUnit) {
        RedisData redisData = new RedisData();
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(timeUnit.toSeconds(time)));
        redisData.setData(value);
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(redisData));
        log.info("设置缓存：{}，过期时间：{}", key, redisData.getExpireTime());
    }

    /**
     * 设置空缓存，过期时间为传入时间的范围随机值（70%~100%）
     */
    public void setWithBlankExpire(String key, Long time, TimeUnit timeUnit) {
        time = RandomUtil.randomLong(time * 7 / 10, time);
        stringRedisTemplate.opsForValue().set(key, "", time, timeUnit);
        log.info("设置空缓存：key={}，过期时间：{}，单位：{}", key, time, timeUnit);
    }

    /** 删除缓存键（更新场景失效用） */
    public void evict(String key) {
        stringRedisTemplate.delete(key);
    }

    /**
     * 纯双检：只读 Redis，若缓存存在且未过期则返回数据，否则返回 null。
     * 不涉及任何锁操作。
     */
    private <R> R doubleCheckCache(String key, Class<R> clazz) {
        String latestData = stringRedisTemplate.opsForValue().get(key);
        if (StrUtil.isNotBlank(latestData)) {
            RedisData redisData = JSONUtil.toBean(latestData, RedisData.class);
            if (LocalDateTime.now().isBefore(redisData.getExpireTime())) {
                return JSONUtil.toBean((JSONObject) redisData.getData(), clazz);
            }
        }
        return null;
    }

    /**
     * 异步重建缓存（任务内自行加锁并解锁）
     */
    private <R, ID> void asyncRebuildCache(String key, String lockKey, ID id,
                                           Function<ID, R> dbFallBack,
                                           Long time, TimeUnit timeUnit, Class<R> clazz) {
        cacheRebuildExecutor.execute(() -> {
            // 异步任务自己抢锁，保证只有一个任务执行
            LockTemplate.LockHandle lock = lockTemplate.tryLock(lockKey,
                    RedisConstants.LOCK_SHOP_TTL, TimeUnit.SECONDS);
            if (lock == null) {
                return; // 抢不到锁说明其他任务正在执行，直接返回
            }
            try {
                // 任务内双检，防止重复执行
                R cached = doubleCheckCache(key, clazz);
                if (cached != null) {
                    return;
                }
                R result = dbFallBack.apply(id);
                if (result != null) {
                    this.setWithLogicalExpire(key, result, time, timeUnit);
                } else {
                    stringRedisTemplate.opsForValue().set(key, "", 2, TimeUnit.MINUTES);
                }
            } catch (Exception e) {
                log.error("异步重建缓存失败，key={}", key, e);
            } finally {
                lock.close();
            }
        });
    }
}
