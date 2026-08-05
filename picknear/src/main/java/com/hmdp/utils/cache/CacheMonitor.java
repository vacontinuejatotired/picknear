package com.hmdp.utils.cache;


import com.github.benmanes.caffeine.cache.LoadingCache;
import com.github.benmanes.caffeine.cache.stats.CacheStats;
import com.hmdp.entity.UserinfoCache;
import jakarta.annotation.Resource;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;


/**
 * 缓存监控工具 — 多级缓存命中率统计与监控
 */
@Component
public class CacheMonitor {

    @Resource
    private LoadingCache<String, UserinfoCache> userinfoCache;

    /**
     * 定时打印Caffeine缓存的统计信息，包括缓存大小、命中率、加载时间等指标，帮助监控缓存性能和使用情况。
     */
    @Scheduled(fixedDelay = 30000) // 每分钟打印一次
    public void printCacheStats() {
        CacheStats stats = userinfoCache.stats();
        System.out.println("========== Caffeine缓存统计 ==========");
        System.out.println("缓存大小: " + userinfoCache.estimatedSize());
        System.out.println("缓存容量: " + userinfoCache.policy().eviction().map(eviction -> eviction.getMaximum()).orElse(-1L));
        System.out.println("缓存项过期时间: " + CaffeineConstants.USERINFO_CACHE_TTL_MINUTES + "分钟");
        System.out.println("总请求数: " + stats.requestCount());
        System.out.println("命中次数: " + stats.hitCount());
        System.out.println("未命中次数: " + stats.missCount());
        System.out.println("命中率: " + String.format("%.2f%%", stats.hitRate() * 100));
        System.out.println("加载成功次数: " + stats.loadSuccessCount());
        System.out.println("加载失败次数: " + stats.loadFailureCount());
        System.out.println("平均加载耗时: " + stats.averageLoadPenalty() / 1_000_000 + "ms");
        System.out.println("=====================================");
    }
}
