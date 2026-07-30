package com.hmdp.utils.cache;

/**
 * Caffeine 缓存常量 — 本地缓存大小、过期时间等配置
 */
public interface CaffeineConstants {
    String USERINFO_CACHE_KEY = "cache:userinfo:";
    Long USERINFO_CACHE_TTL_MINUTES = 20L;
    Long USERINFO_ASYNC_REFRESH_THRESHOLD_MINUTES = 10L;
    Long USERINFO_CACHE_TTL_SECONDS = (60 * 20L);

    String TOKEN_VALID_VERSION_CACHE_KEY = "cache:token:version:";
    Long TOKEN_VALID_VERSION_CACHE_TTL_MINUTES = 5L;
    Long TOKEN_VALID_VERSION_CACHE_TTL_SECONDS = (60 * 5L);

    Integer TOKEN_VERSION_CACHE_HIT_MATCH = 0;
    Integer TOKEN_VERSION_CACHE_HIT_MISMATCH = 1;
    Integer TOKEN_VERSION_CACHE_MISS = 2;
}
