package com.hmdp.utils.redis;

/**
 * Redis 键前缀与过期时间常量 — 统一管理所有 Redis Key 格式
 */
public class RedisConstants {
    public static final String LOGIN_CODE_KEY = "login:code:";
    public static final Long LOGIN_CODE_TTL = 2L;
    public static final String LOGIN_USER_KEY = "login:token:access:";
    public static final String LOGIN_USERINFO_MAP = "login:userinfo:";
    public static final String LOGIN_REFRESH_USER_KEY = "login:token:refresh:";
    public static final String LOGIN_VALID_VERSION_KEY = "token:version:valid:";
//    public static final Long LOGIN_USER_TTL = 36000L;
    public static final String CURRENT_TOKEN_VERSION_KEY = "token:version:current:";
    public static final Long CACHE_NULL_TTL = 2L;
    public static final Long LOGIN_TOKEN_TTL_SECONDS = 1800L;
    public static final Long LOGIN_REFRESHTOKEN_TTL_SECONDS = (7 * 24 * 60 * 60L);
    public static final Long NEW_VERSION_TTL_SECONDS = (8*24*60*60L);
    //过期时间暂时设置为1分钟方便测试
    public static final Long LOGIN_JWT_TTL_MINUTES = 30L;
    public static final Long CACHE_SHOP_TTL = 30L;
    public static final String CACHE_SHOP_KEY = "cache:shop:";
    public static final String CACHE_BLOG_KEY = "cache:blog:";
    public static final Long CACHE_BLOG_TTL = 30L;

    public static final String LOCK_SHOP_KEY = "lock:shop:";
    public static final Long LOCK_SHOP_TTL = 10L;

    public static final String SECKILL_STOCK_KEY = "seckill:stock:";
    public static final String SECKILL_ORDER_EXIST_USER_ZSET_KEY = "seckill:order:";
    public static final String SECKILL_ORDERIFNO_KEY = "seckill:order:info:";
    public static final String BLOG_LIKED_KEY = "blog:liked:";
    public static final String USER_LIKED_KEY = "user:liked:";
    public static final String FEED_KEY = "feed:";
    public static final String SHOP_GEO_KEY = "shop:geo:";
    public static final String USER_SIGN_KEY = "sign:";
    public static final String CACHE_SHOPTYPE_KEY = "cache:shopType";

    // 密码登录限频
    public static final String LOGIN_FAIL_IP_KEY = "login:fail:ip:";
    public static final Long LOGIN_FAIL_IP_TTL = 60L;        // 60 秒
    public static final int LOGIN_FAIL_IP_MAX = 5;           // 每分钟最多 5 次
    public static final String LOGIN_FAIL_COUNT_KEY = "login:fail:count:";
    public static final Long LOGIN_FAIL_COUNT_TTL = 86400L;  // 24 小时
    public static final int LOGIN_FAIL_COUNT_LOCK = 10;      // 连续 10 次锁定
    public static final String LOGIN_LOCK_KEY = "login:lock:";
    public static final Long LOGIN_LOCK_TTL = 1800L;         // 30 分钟

    // 关注模块
    public static final String FOLLOWS_KEY = "follows:";
    public static final String FOLLOWERS_KEY = "followers:";
    public static final String FOLLOWS_EMPTY_KEY = "follows:empty:";
    public static final String USER_EXISTS_KEY = "user:exists:";
    public static final String LOCK_FOLLOW_KEY = "lock:follow:";
    public static final Long FOLLOWS_TTL = 3600L;            // 1 小时
    public static final Long FOLLOWS_EMPTY_TTL = 10L;        // 10 秒
    public static final Long USER_EXISTS_TTL = 10L;          // 10 秒
    public static final Long LOCK_FOLLOW_TTL = 10L;          // 10 秒
}
