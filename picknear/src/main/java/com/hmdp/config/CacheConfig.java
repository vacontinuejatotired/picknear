package com.hmdp.config;

import com.github.benmanes.caffeine.cache.CacheLoader;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.LoadingCache;
import com.hmdp.auth.entity.TokenVersionCache;
import com.hmdp.user.entity.UserinfoCache;
import com.hmdp.utils.cache.BatchLoadCache;
import com.hmdp.utils.cache.CaffeineConstants;
import jakarta.annotation.Resource;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;
import org.springframework.lang.Nullable;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;

/**
 * 多级缓存配置 — Caffeine本地缓存（用户信息、Token版本号）定义 + 缓存重建线程池
 */
@Slf4j
@Configuration
@EnableCaching
public class CacheConfig {

    /** 缓存异步重建线程池（Spring 托管有界池，替代 CacheClient 静态 Executors，P4-S2） */
    @Bean("cacheRebuildExecutor")
    public ThreadPoolTaskExecutor cacheRebuildExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(4);
        executor.setQueueCapacity(200);
        executor.setThreadNamePrefix("cache-rebuild-");
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(10);
        return executor;
    }

    @Resource
    @Lazy
    private BatchLoadCache batchLoadCache;

    @Bean("userinfoCache")
    public LoadingCache<String, UserinfoCache> userinfoCache() {
        return Caffeine.newBuilder().maximumSize(10000).expireAfterWrite(CaffeineConstants.USERINFO_CACHE_TTL_MINUTES, TimeUnit.MINUTES)
                .initialCapacity(1000).recordStats().refreshAfterWrite(CaffeineConstants.USERINFO_ASYNC_REFRESH_THRESHOLD_MINUTES,TimeUnit.MINUTES).build(new CacheLoader<String, UserinfoCache>() {
                    @Override
                    public @Nullable UserinfoCache load(@NonNull String key) throws Exception {
                        // 缓存缺失时：返回空值不阻塞，触发异步批量加载（由 BatchLoadCache 定时任务填充）
                        Long userId = Long.valueOf(key.substring(CaffeineConstants.USERINFO_CACHE_KEY.length()));
                        batchLoadCache.saveFuture(userId);
                        return new UserinfoCache(userId, "", "");
                    }

                    @Override
                    public @NonNull CompletableFuture<UserinfoCache> asyncReload(
                            @NonNull String key,
                            @NonNull UserinfoCache oldValue,
                            @NonNull Executor executor) {

                        Long userId = Long.parseLong(key.replace(CaffeineConstants.USERINFO_CACHE_KEY, ""));
                        // 刷新时也只需要触发异步更新
                        return CompletableFuture.supplyAsync(() -> {
                            batchLoadCache.saveFuture(userId);  // 再次触发
                            return oldValue;  // 刷新期间返回旧值
                        }, executor);
                    }
                });
    }

    @Bean("tokenValidVersionCache")
    public LoadingCache<String, TokenVersionCache> tokenValidVersionCache() {
        return Caffeine.newBuilder().maximumSize(10000)
                .expireAfterWrite(CaffeineConstants.TOKEN_VALID_VERSION_CACHE_TTL_MINUTES, TimeUnit.MINUTES)
                .initialCapacity(1000)
                .recordStats()
                .build(new CacheLoader<String, TokenVersionCache>() {
                    @Override
                    public @Nullable TokenVersionCache load(@NonNull String key) {
                        Long userId = Long.valueOf(key.substring(CaffeineConstants.TOKEN_VALID_VERSION_CACHE_KEY.length()));
                        TokenVersionCache tokenVersionCache = new TokenVersionCache();
                        tokenVersionCache.setUserId(userId);
                        tokenVersionCache.setVersion(-1L);
                        tokenVersionCache.setStatus(CaffeineConstants.TOKEN_VERSION_CACHE_MISS);
                        return tokenVersionCache;
                    }
                });
    }
}
