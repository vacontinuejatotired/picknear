package com.hmdp.common.cache;

import com.hmdp.common.lock.LockTemplate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * CacheManager — 通用单键缓存门面测试（P4-S2 新增组件）。
 * 覆盖：queryWithCache 命中/空值/回源/空结果、逻辑过期降级。
 */
@ExtendWith(MockitoExtension.class)
class CacheManagerTest {

    @Mock
    private StringRedisTemplate stringRedisTemplate;

    @Mock
    private ValueOperations<String, String> valueOps;

    @Mock
    private LockTemplate lockTemplate;

    @Mock
    private Executor cacheRebuildExecutor;

    private CacheManager cacheManager;

    @BeforeEach
    void setUp() {
        cacheManager = new CacheManager();
        ReflectionTestUtils.setField(cacheManager, "stringRedisTemplate", stringRedisTemplate);
        ReflectionTestUtils.setField(cacheManager, "lockTemplate", lockTemplate);
        ReflectionTestUtils.setField(cacheManager, "cacheRebuildExecutor", cacheRebuildExecutor);
        when(stringRedisTemplate.opsForValue()).thenReturn(valueOps);
    }

    @Test
    void queryWithCache_should_return_cached_value_without_loading() {
        when(valueOps.get("cache:user:1")).thenReturn("{\"id\":1,\"nickName\":\"张三\"}");
        String[] loaderCalled = {""};

        Object result = cacheManager.queryWithCache(1L, TestDto.class, "cache:user:",
                id -> { loaderCalled[0] = "called"; return new TestDto(); }, 30L, TimeUnit.MINUTES);

        assertThat(result).isNotNull();
        assertThat(loaderCalled[0]).as("缓存命中不应回源").isEmpty();
    }

    @Test
    void queryWithCache_should_return_null_on_blank_cache() {
        when(valueOps.get("cache:user:1")).thenReturn("");
        String[] loaderCalled = {""};

        Object result = cacheManager.queryWithCache(1L, TestDto.class, "cache:user:",
                id -> { loaderCalled[0] = "called"; return new TestDto(); }, 30L, TimeUnit.MINUTES);

        assertThat(result).as("空值缓存命中应返回 null（防穿透）").isNull();
        assertThat(loaderCalled[0]).as("空值缓存不应回源").isEmpty();
    }

    @Test
    void queryWithCache_should_load_and_write_when_miss() {
        when(valueOps.get("cache:user:1")).thenReturn(null);
        TestDto dto = new TestDto();
        dto.setId(1L);

        Object result = cacheManager.queryWithCache(1L, TestDto.class, "cache:user:",
                id -> dto, 30L, TimeUnit.MINUTES);

        assertThat(result).isSameAs(dto);
        // 回源后写入缓存（随机 TTL，70%~100%）
        verify(valueOps).set(eq("cache:user:1"), contains("id"), anyLong(), eq(TimeUnit.MINUTES));
    }

    @Test
    void queryWithCache_should_write_blank_when_loader_returns_null() {
        when(valueOps.get("cache:user:99")).thenReturn(null);

        Object result = cacheManager.queryWithCache(99L, TestDto.class, "cache:user:",
                id -> null, 30L, TimeUnit.MINUTES);

        assertThat(result).isNull();
        // DB 无结果 → 写空值缓存（短 TTL 防穿透）
        verify(valueOps).set(eq("cache:user:99"), eq(""), eq(2L), eq(TimeUnit.MINUTES));
    }

    @Test
    void queryWithLogicExpire_should_return_null_when_lock_not_acquired() {
        when(valueOps.get("cache:shop:5")).thenReturn(null);
        when(lockTemplate.tryLock(eq("lock:shop:5"), anyLong(), any(TimeUnit.class))).thenReturn(null);

        Object result = cacheManager.queryWithLogicExpire("cache:shop:", 5L, TestDto.class,
                id -> new TestDto(), 20L, TimeUnit.SECONDS);

        assertThat(result).as("抢锁失败应降级返回 null").isNull();
        // 未拿到锁不提交异步重建（防止重建风暴）
        verify(cacheRebuildExecutor, never()).execute(any());
    }

    static class TestDto {
        private Long id;
        private String nickName;

        public Long getId() { return id; }
        public void setId(Long id) { this.id = id; }
        public String getNickName() { return nickName; }
        public void setNickName(String nickName) { this.nickName = nickName; }
    }
}
