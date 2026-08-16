package com.hmdp.utils.cache;


import com.github.benmanes.caffeine.cache.LoadingCache;
import com.github.benmanes.caffeine.cache.stats.CacheStats;
import com.hmdp.user.entity.UserinfoCache;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;


/**
 * 缓存监控工具 — 多级缓存命中率统计与监控
 */
@Slf4j
@Component
public class CacheMonitor {

    @Resource
    private LoadingCache<String, UserinfoCache> userinfoCache;

    /**
     * 定时打印Caffeine缓存的统计信息，包括缓存大小、命中率、加载时间等指标，帮助监控缓存性能和使用情况。
     */
    @Scheduled(fixedDelay = 30000) // 每 30 秒打印一次
    public void printCacheStats() {
        CacheStats stats = userinfoCache.stats();
        log.debug("========== Caffeine缓存统计 ==========");
        log.debug("缓存大小: {}", userinfoCache.estimatedSize());
        log.debug("缓存容量: {}", userinfoCache.policy().eviction().map(eviction -> eviction.getMaximum()).orElse(-1L));
        log.debug("缓存项过期时间: {}分钟", CaffeineConstants.USERINFO_CACHE_TTL_MINUTES);
        log.debug("总请求数: {}", stats.requestCount());
        log.debug("命中次数: {}", stats.hitCount());
        log.debug("未命中次数: {}", stats.missCount());
        log.debug("命中率: {}%", String.format("%.2f", stats.hitRate() * 100));
        log.debug("加载成功次数: {}", stats.loadSuccessCount());
        log.debug("加载失败次数: {}", stats.loadFailureCount());
        log.debug("平均加载耗时: {}ms", stats.averageLoadPenalty() / 1_000_000);
        log.debug("=====================================");
    }
}
