package com.hmdp.service.impl;

import cn.hutool.json.JSONUtil;
import com.github.benmanes.caffeine.cache.LoadingCache;
import com.hmdp.dto.LuaResult;
import com.hmdp.dto.TokenPair;
import com.hmdp.dto.ValidationResult;
import com.hmdp.entity.TokenVersionCache;
import com.hmdp.entity.UserinfoCache;
import com.hmdp.enums.TokenRefreshCode;
import com.hmdp.service.AuthService;
import com.hmdp.utils.cache.BatchLoadCache;
import com.hmdp.utils.cache.CaffeineConstants;
import com.hmdp.utils.redis.RedisConstants;
import com.hmdp.utils.redis.RedisIdWorker;
import com.hmdp.utils.security.JwtUtil;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.JwtException;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.TimeUnit;

/**
 * 认证服务实现 — Token 生成/校验/刷新/注销/验证码消费，无 HTTP 依赖
 */
@Slf4j
@Service
public class AuthServiceImpl implements AuthService {

    @Resource
    private JwtUtil jwtUtil;
    @Resource
    private StringRedisTemplate stringRedisTemplate;
    @Resource
    private RedisIdWorker redisIdWorker;
    @Resource
    private BatchLoadCache batchLoadCache;
    @Resource(name = "refreshDeadTokenScript")
    private DefaultRedisScript<Long> refreshDeadTokenScript;
    @Resource(name = "refreshDeadlineTokenScript")
    private DefaultRedisScript<Long> refreshDeadlineTokenScript;
    @Resource(name = "userinfoCache")
    private LoadingCache<String, UserinfoCache> userinfoCaffeine;
    @Resource(name = "tokenValidVersionCache")
    private LoadingCache<String, TokenVersionCache> tokenValidVersionCache;
    @Resource(name = "consumeVerifyCodeScript")
    private DefaultRedisScript<String> consumeVerifyCodeScript;
    @Resource(name = "REDIS_LOGIN_SET_TOKEN")
    private DefaultRedisScript<String> REDIS_LOGIN_SET_TOKEN;

    // ==================== 登录生成 ====================

    @Override
    public TokenPair generateTokenPair(Long userId) {
        Long version = redisIdWorker.nextVersion(userId);
        String accessToken = jwtUtil.generateToken(userId,
                RedisConstants.LOGIN_JWT_TTL_MINUTES, ChronoUnit.MINUTES, version);
        String refreshToken = cn.hutool.core.lang.UUID.randomUUID().toString().replace("-", "");

        List<String> keys = Arrays.asList(
                RedisConstants.LOGIN_USER_KEY + userId,
                RedisConstants.LOGIN_REFRESH_USER_KEY + userId,
                RedisConstants.LOGIN_VALID_VERSION_KEY + userId,
                RedisConstants.CURRENT_TOKEN_VERSION_KEY + userId
        );
        List<String> argv = Arrays.asList(
                accessToken, refreshToken, version.toString(),
                String.valueOf(60L * RedisConstants.LOGIN_JWT_TTL_MINUTES),
                RedisConstants.LOGIN_REFRESHTOKEN_TTL_SECONDS.toString(),
                RedisConstants.LOGIN_REFRESHTOKEN_TTL_SECONDS.toString(),
                RedisConstants.NEW_VERSION_TTL_SECONDS.toString()
        );

        try {
            String execute = stringRedisTemplate.execute(REDIS_LOGIN_SET_TOKEN, keys, argv.toArray());
            if (execute == null) {
                log.error("generateTokenPair Lua 返回 null, userId={}", userId);
            } else {
                LuaResult luaResult = JSONUtil.toBean(execute, LuaResult.class);
                if (luaResult.getCode() != 1) {
                    log.warn("generateTokenPair Lua 返回异常 code={}, userId={}", luaResult.getCode(), userId);
                }
            }
        } catch (Exception e) {
            log.error("generateTokenPair Lua 执行失败, userId={}", userId, e);
        }

        log.info("【生成Token】userId={}, version={}, accessToken前20={}",
                userId, version, accessToken.substring(0, Math.min(20, accessToken.length())));
        updateLocalVersionCache(userId, version);
        return new TokenPair(accessToken, refreshToken, version);
    }

    // ==================== Token 校验 ====================

    @Override
    public ValidationResult validateAccessToken(String token) {
        ValidationResult.ValidationResultBuilder builder = ValidationResult.builder().valid(false);
        try {
            Claims claims = jwtUtil.validateAndGetClaimFromToken(token);
            Long userId = claims.get("userId", Long.class);
            Long versionFromToken = claims.get("version", Long.class);
            builder.userId(userId).version(versionFromToken);

            // Caffeine 快速拒绝
            String versionKey = CaffeineConstants.TOKEN_VALID_VERSION_CACHE_KEY + userId;
            TokenVersionCache localCache = tokenValidVersionCache.getIfPresent(versionKey);
            if (localCache != null && !localCache.getVersion().equals(versionFromToken)) {
                log.info("Caffeine 版本不匹配，直接拒绝 userId={}", userId);
                return builder.valid(false).build();
            }

            // Redis 最终校验
            String redisVersion = stringRedisTemplate.opsForValue()
                    .get(RedisConstants.LOGIN_VALID_VERSION_KEY + userId);
            if (redisVersion == null) {
                log.info("Redis 版本不存在，拒绝 userId={}", userId);
                return builder.valid(false).build();
            }
            if (Long.parseLong(redisVersion) > versionFromToken) {
                log.warn("Redis 版本校验不通过 userId={}, Redis={}, token={}",
                        userId, redisVersion, versionFromToken);
                return builder.valid(false).build();
            }

            // 更新本地版本缓存
            TokenVersionCache cache = new TokenVersionCache();
            cache.setUserId(userId);
            cache.setVersion(versionFromToken);
            tokenValidVersionCache.put(versionKey, cache);

            // 判断是否临期
            Date expiration = claims.getExpiration();
            long timeToExpire = expiration.getTime() - System.currentTimeMillis();
            boolean needsRefresh = timeToExpire > 0
                    && timeToExpire < cn.hutool.core.util.RandomUtil.randomLong(5L, 10L) * 60 * 1000;

            return builder.valid(true).needsRefresh(needsRefresh).build();

        } catch (ExpiredJwtException e) {
            Long versionFromToken = e.getClaims().get("version", Long.class);
            log.info("Token 已过期，userId={}，version={}", e.getClaims().get("userId"), versionFromToken); 
            return builder.userId(e.getClaims().get("userId", Long.class))
                    .version(versionFromToken)
                    .needsRefresh(true).build();
        } catch (JwtException e) {
            log.warn("JWT 校验失败: {}", e.getMessage());
            return builder.valid(false).build();
        }
    }

    // ==================== Token 刷新 ====================

    @Override
    public TokenPair refreshTokenPair(String accessToken, String refreshToken, Long userId, Long oldVersion, boolean isExpired) {
        if (refreshToken == null) {
            log.warn("refreshToken is null, cannot refresh userId={}", userId);
            return null;
        }
        if (isExpired) {
            return doExpiredRefresh(accessToken, refreshToken, userId, oldVersion);
        } else {
            return doDeadlineRefresh(accessToken, refreshToken, userId, oldVersion);
        }
    }

    /**
     * 临期刷新 — token 未过期但剩余时间 < 5-10 分钟
     * 保持 version 不变，只换 access token
     * 分布式锁由拦截器保证，此处只做刷新
     */
    private TokenPair doDeadlineRefresh(String accessToken, String refreshToken, Long userId, Long oldVersion) {
        // 锁内生成 JWT + 直接写入 Redis（不调 Lua，避免重复校验）
        String newToken = jwtUtil.generateToken(userId,
                RedisConstants.LOGIN_JWT_TTL_MINUTES, ChronoUnit.MINUTES, oldVersion);
        String newRefreshToken = cn.hutool.core.lang.UUID.randomUUID().toString().replace("-", "");

        stringRedisTemplate.opsForValue().set(RedisConstants.LOGIN_USER_KEY + userId,
                newToken, 30, TimeUnit.MINUTES);
        stringRedisTemplate.opsForValue().set(RedisConstants.LOGIN_REFRESH_USER_KEY + userId,
                newRefreshToken, 7, TimeUnit.DAYS);

        updateLocalVersionCache(userId, oldVersion);
        log.info("临期刷新成功 userId={}", userId);
        return new TokenPair(newToken, newRefreshToken, oldVersion);
    }

    /**
     * 过期刷新 — JWT 已过期，生成新版本 + 新 refreshToken + 新 accessToken
     */
    private TokenPair doExpiredRefresh(String accessToken, String refreshToken, Long userId, Long oldVersion) {
        String newRefreshToken = cn.hutool.core.lang.UUID.randomUUID().toString().replace("-", "");
        Long newVersion = oldVersion;
        String newToken = jwtUtil.generateToken(userId,
                RedisConstants.LOGIN_JWT_TTL_MINUTES, ChronoUnit.MINUTES, newVersion);
        // 不 bump version（复用旧版本），分布式锁已防并发竞态，直接 SET 无需 Lua
        stringRedisTemplate.opsForValue().set(RedisConstants.LOGIN_USER_KEY + userId,
                newToken, 30, TimeUnit.MINUTES);
        stringRedisTemplate.opsForValue().set(RedisConstants.LOGIN_REFRESH_USER_KEY + userId,
                newRefreshToken, 7, TimeUnit.DAYS);

        updateLocalVersionCache(userId, oldVersion);
        log.info("过期刷新成功 userId={}, version={} (不变)", userId, oldVersion);
        return new TokenPair(newToken, newRefreshToken, oldVersion);
    }

    // ==================== 登出 ====================
    //暴力删除是否可行？
    //需列出边界情况
    //告诉我这种情况下仍能登录成功的场景
    @Override
    public void revokeTokens(Long userId) {
        stringRedisTemplate.delete(RedisConstants.LOGIN_USER_KEY + userId);
        stringRedisTemplate.delete(RedisConstants.LOGIN_REFRESH_USER_KEY + userId);
        stringRedisTemplate.delete(RedisConstants.LOGIN_VALID_VERSION_KEY + userId);
        stringRedisTemplate.delete(RedisConstants.CURRENT_TOKEN_VERSION_KEY + userId);
        // 清除本地 Caffeine 缓存
        String userInfoKey = CaffeineConstants.USERINFO_CACHE_KEY + userId;
        userinfoCaffeine.invalidate(userInfoKey);
        String versionKey = CaffeineConstants.TOKEN_VALID_VERSION_CACHE_KEY + userId;
        tokenValidVersionCache.invalidate(versionKey);
        log.info("【登出】已清除 userId={} 的所有 Token、Version 和本地缓存", userId);
    }

    // ==================== 验证码 ====================

    @Override
    public boolean consumeVerifyCode(String phone, String code) {
        if (code == null) {
            return false;
        }
        String tempCode = stringRedisTemplate.execute(consumeVerifyCodeScript,
                List.of(RedisConstants.LOGIN_CODE_KEY + phone));
        return code.equals(tempCode);
    }

    // ==================== 用户缓存 ====================

    // cacheUserInfo 已废弃：写入 login:userinfo:{id} 但从未被读取。
    // 用户信息缓存统一由 BatchLoadCache 管理（cache:userinfo:{id}）。

    // ==================== 本地缓存更新 ====================

    private void updateLocalVersionCache(Long userId, Long version) {
        String versionKey = CaffeineConstants.TOKEN_VALID_VERSION_CACHE_KEY + userId;
        TokenVersionCache tokenVersionCache = new TokenVersionCache();
        tokenVersionCache.setUserId(userId);
        tokenVersionCache.setVersion(version);
        tokenVersionCache.setStatus(CaffeineConstants.TOKEN_VERSION_CACHE_HIT_MATCH);
        tokenValidVersionCache.put(versionKey, tokenVersionCache);
    }
}
