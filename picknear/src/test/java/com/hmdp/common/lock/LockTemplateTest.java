package com.hmdp.common.lock;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * LockTemplate — 分布式锁模板测试（P4-S1 新增组件）。
 * 覆盖：加锁成功/失败、Lua 校验释放、withLock 便捷模板。
 */
@ExtendWith(MockitoExtension.class)
class LockTemplateTest {

    @Mock
    private StringRedisTemplate stringRedisTemplate;

    @Mock
    private DefaultRedisScript<Long> redisUnlockScript;

    @Mock
    private ValueOperations<String, String> valueOps;

    private LockTemplate lockTemplate;

    @BeforeEach
    void setUp() {
        lockTemplate = new LockTemplate();
        ReflectionTestUtils.setField(lockTemplate, "stringRedisTemplate", stringRedisTemplate);
        ReflectionTestUtils.setField(lockTemplate, "redisUnlockScript", redisUnlockScript);
        when(stringRedisTemplate.opsForValue()).thenReturn(valueOps);
    }

    @Test
    void tryLock_should_return_handle_when_setnx_ok() {
        when(valueOps.setIfAbsent(eq("lock:a"), anyString(), eq(3L), eq(TimeUnit.SECONDS))).thenReturn(true);

        LockTemplate.LockHandle handle = lockTemplate.tryLock("lock:a", 3, TimeUnit.SECONDS);

        assertThat(handle).as("SETNX 成功应返回锁句柄").isNotNull();
    }

    @Test
    void tryLock_should_return_null_when_setnx_fails() {
        when(valueOps.setIfAbsent(eq("lock:a"), anyString(), eq(3L), eq(TimeUnit.SECONDS))).thenReturn(false);

        LockTemplate.LockHandle handle = lockTemplate.tryLock("lock:a", 3, TimeUnit.SECONDS);

        assertThat(handle).as("SETNX 失败应返回 null（调用方降级）").isNull();
    }

    @Test
    void close_should_release_with_lua_and_owner_value() {
        when(valueOps.setIfAbsent(eq("lock:a"), anyString(), eq(3L), eq(TimeUnit.SECONDS))).thenReturn(true);
        LockTemplate.LockHandle handle = lockTemplate.tryLock("lock:a", 3, TimeUnit.SECONDS);

        handle.close();

        // 释放走 Lua 校验：key + 所有者 value（非 delete 直接删）
        verify(stringRedisTemplate).execute(eq(redisUnlockScript), anyList(), anyString());
        // 幂等：二次 close 不再执行
        handle.close();
        verify(stringRedisTemplate, times(1)).execute(eq(redisUnlockScript), anyList(), anyString());
    }

    @Test
    void withLock_should_return_null_when_lock_not_acquired() {
        when(valueOps.setIfAbsent(eq("lock:b"), anyString(), eq(3L), eq(TimeUnit.SECONDS))).thenReturn(false);

        String result = lockTemplate.withLock("lock:b", 3, TimeUnit.SECONDS, () -> "done");

        assertThat(result).as("抢锁失败 withLock 应返回 null").isNull();
    }

    @Test
    void withLock_should_execute_action_and_release() {
        when(valueOps.setIfAbsent(eq("lock:b"), anyString(), eq(3L), eq(TimeUnit.SECONDS))).thenReturn(true);

        String result = lockTemplate.withLock("lock:b", 3, TimeUnit.SECONDS, () -> "done");

        assertThat(result).as("抢锁成功应执行 action").isEqualTo("done");
        verify(stringRedisTemplate).execute(eq(redisUnlockScript), anyList(), anyString());
    }
}
