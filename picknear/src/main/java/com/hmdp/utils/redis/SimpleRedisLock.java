package com.hmdp.utils.redis;

import cn.hutool.core.util.BooleanUtil;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.UUID;
import java.util.concurrent.TimeUnit;


/**
 * 简单 Redis 分布式锁实现 — 基于 SETNX + Lua 释放锁
 */
public class SimpleRedisLock implements ILock {
    private StringRedisTemplate redisTemplate;
    private String name;
    private static final String KEY_PREFIX = "lock:";
    private static final String  ID_PREFIX = UUID.randomUUID().toString() +"-";
    private static final DefaultRedisScript<Long>REDIS_UNLOCK_SCRIPT;
    static {
        REDIS_UNLOCK_SCRIPT = new DefaultRedisScript<>();
        REDIS_UNLOCK_SCRIPT.setResultType(Long.class);
        REDIS_UNLOCK_SCRIPT.setLocation(new ClassPathResource("RedisUnLock.lua"));
    }
    public SimpleRedisLock(StringRedisTemplate redisTemplate, String name) {
        this.redisTemplate = redisTemplate;
        this.name = name;
    }
    @Override
    public boolean tryLock(long timeoutSec) {
        String threadId = ID_PREFIX + Thread.currentThread().getId();
        Boolean success = redisTemplate.opsForValue().setIfAbsent(KEY_PREFIX+name, threadId, timeoutSec, TimeUnit.SECONDS);
        return BooleanUtil.isTrue(success);
    }

    @Override
    public void unlock() {
        redisTemplate.execute(REDIS_UNLOCK_SCRIPT,
                Collections.singletonList(KEY_PREFIX+name),
                ID_PREFIX + Thread.currentThread().getId());

    }
//    @Override
//    public void unlock() {
//        String threadId = ID_PREFIX + Thread.currentThread().getId();
//        String id = redisTemplate.opsForValue().get(KEY_PREFIX + threadId);
//        if(threadId.equals(id)) {
//            redisTemplate.delete(KEY_PREFIX + name);
//        }
//    }
}
