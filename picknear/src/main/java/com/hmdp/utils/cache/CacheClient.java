package com.hmdp.utils.cache;

import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.RandomUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.hmdp.entity.Shop;
import com.hmdp.utils.redis.RedisConstants;
import lombok.extern.slf4j.Slf4j;
import org.apache.ibatis.annotations.Select;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

/**
 * 缓存客户端工具 — 封装 Redis 缓存操作，提供缓存穿透/击穿解决方案
 */
@Slf4j
@Component
public class CacheClient {
    private final StringRedisTemplate stringRedisTemplate;

    public CacheClient(StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
    }

    public void set(String key, Object value, Long time, TimeUnit timeUnit) {
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(value), time, timeUnit);
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
     * 
     * @param key
     * @param time
     * @param timeUnit
     */
    public void setWithBlankExpire(String key, Long time, TimeUnit timeUnit) {
        time = RandomUtil.randomLong(time * 7 / 10, time);
        stringRedisTemplate.opsForValue().set(key, "", time, timeUnit);
        log.info("设置空缓存：key={}，过期时间：{}，单位：{}", key, time, timeUnit);
    }

    public <R, ID> R queryById(ID id, Class<R> clazz, String keyPrefix, Function<ID, R> dbFallBack, Long time,
            TimeUnit timeUnit) {
        String key = keyPrefix + id;
        String Json = stringRedisTemplate.opsForValue().get(key);
        if (StrUtil.isNotBlank(Json)) {
            return JSONUtil.toBean(Json, clazz, false);
        }
        // 判断命中的是不是空值
        if (Json != null) {
            return null;
        }
        R result = dbFallBack.apply(id);
        if (result == null) {
            stringRedisTemplate.opsForValue().set(key, "", RedisConstants.CACHE_NULL_TTL, TimeUnit.MINUTES);
            return null;
        }
        this.set(key, result, time, timeUnit);
        return result;
    }

    private static final ExecutorService CACHE_REBUILD_THREAD_POOL = Executors.newFixedThreadPool(10);

    public <R, ID> R queryWithLogicExpire(String keyPrefix, ID id, Class<R> clazz,
            Function<ID, R> dbFallBack,
            Long time1, TimeUnit timeUnit) {
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
            boolean isLock = tryLock(lockKey);
            if (isLock) {
                try {
                    // 双检：再次检查是否被其他线程刚写入
                    R cached = doubleCheckCache(key, clazz);
                    if (cached != null) {
                        return cached;
                    }
                    // 未命中，需要重建，但不在持有锁时提交异步任务
                } finally {
                    unlock(lockKey); // 立即释放锁
                }
                // 锁已释放，提交异步任务（任务内会重新抢锁执行重建）
                asyncRebuildCache(key, lockKey, id, dbFallBack, time1, timeUnit, clazz);
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
        boolean isLock = tryLock(lockKey);
        if (isLock) {
            try {
                R updated = doubleCheckCache(key, clazz);
                if (updated != null) {
                    return updated; // 被其他线程更新了
                }
            } finally {
                unlock(lockKey); // 释放锁
            }
            // 释放锁后提交异步重建
            asyncRebuildCache(key, lockKey, id, dbFallBack, time1, timeUnit, clazz);
        }
        // 返回旧数据（可能已过期，但允许）
        return r;
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
            Long time1, TimeUnit timeUnit, Class<R> clazz) {
        CACHE_REBUILD_THREAD_POOL.submit(() -> {
            // 异步任务自己抢锁，保证只有一个任务执行
            if (!tryLock(lockKey)) {
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
                    this.setWithLogicalExpire(key, result, time1, timeUnit);
                } else {
                    stringRedisTemplate.opsForValue().set(key, "", 2, TimeUnit.MINUTES);
                }
            } catch (Exception e) {
                log.error("异步重建缓存失败，key={}", key, e);
            } finally {
                unlock(lockKey);
            }
        });
    }

    private boolean tryLock(String key) {
        Boolean flag = stringRedisTemplate.opsForValue().setIfAbsent(key, "1", RedisConstants.LOCK_SHOP_TTL,
                TimeUnit.SECONDS);
        // 拆箱要判空，防止NPE
        return BooleanUtil.isTrue(flag);
    }

    /**
     * 释放锁
     *
     * @param key
     */
    private void unlock(String key) {
        // TODO 释放之前需要检查谁持有锁，只有当前线程持有锁才能释放，推荐lua脚本操作
        stringRedisTemplate.delete(key);
    }

   
}
