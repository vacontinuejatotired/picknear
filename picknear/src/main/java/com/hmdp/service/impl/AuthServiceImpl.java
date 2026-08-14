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
    @Resource(name = "readCurrentTokenScript")
    private DefaultRedisScript<List> readCurrentTokenScript;
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
        if (refreshToken == null || refreshToken.isEmpty()) {
            log.warn("refreshToken is null, cannot refresh userId={}", userId);
            return null;
        }

        // 生成新双 token（版本复用 oldVersion，版本一致性由 Lua 守卫保证）
        String newToken = jwtUtil.generateToken(userId,
                RedisConstants.LOGIN_JWT_TTL_MINUTES, ChronoUnit.MINUTES, oldVersion);
        String newRefreshToken = cn.hutool.core.lang.UUID.randomUUID().toString().replace("-", "");

        // KEYS 顺序必须匹配 RefreshExpiredToken.lua
        List<String> keys = Arrays.asList(
                RedisConstants.LOGIN_REFRESH_USER_KEY + userId,   // KEYS[1] refreshKey
                RedisConstants.LOGIN_USER_KEY + userId,           // KEYS[2] tokenKey
                RedisConstants.LOGIN_VALID_VERSION_KEY + userId,  // KEYS[3] validVersionKey
                RedisConstants.CURRENT_TOKEN_VERSION_KEY + userId // KEYS[4] newVersionKey
        );
        // ARGV 顺序必须匹配 RefreshExpiredToken.lua
        List<String> argv = Arrays.asList(
                refreshToken,                                             // ARGV[1] oldRefreshToken
                newRefreshToken,                                          // ARGV[2] newRefreshToken
                RedisConstants.LOGIN_REFRESHTOKEN_TTL_SECONDS.toString(), // ARGV[3] refreshExpire (7d)
                newToken,                                                 // ARGV[4] newToken
                String.valueOf(60L * RedisConstants.LOGIN_JWT_TTL_MINUTES), // ARGV[5] tokenExpire (30min)
                oldVersion.toString(),                                    // ARGV[6] version
                RedisConstants.LOGIN_REFRESHTOKEN_TTL_SECONDS.toString(), // ARGV[7] versionExpire（滑动 7d）
                oldVersion.toString(),                                    // ARGV[8] newVersion = oldVersion（不 bump）
                RedisConstants.NEW_VERSION_TTL_SECONDS.toString()         // ARGV[9] newVersionExpire (8d)
        );

        Long code;
        try {
            code = stringRedisTemplate.execute(refreshDeadTokenScript, keys, argv.toArray());
        } catch (Exception e) {
            log.error("Lua 刷新执行失败 userId={}", userId, e);
            return null;
        }

        if (code == null) {
            log.warn("Lua 刷新返回 null userId={}", userId);
            return null;
        }

        // 返回码解释（状态码透传的基础）：统一走 TokenRefreshCode 枚举，避免魔法数字
        TokenRefreshCode refreshCode = TokenRefreshCode.fromCode(code);
        if (refreshCode == TokenRefreshCode.SUCCESS) {
            updateLocalVersionCache(userId, oldVersion);
            log.info("{}刷新成功 userId={}, version={}", isExpired ? "过期" : "临期", userId, oldVersion);
            return new TokenPair(newToken, newRefreshToken, oldVersion);
        }

        if (refreshCode == TokenRefreshCode.REFRESH_TOKEN_MISMATCH) {
            // C2 并发容错：RT 不匹配，但同一会话可能已被并发刷新轮换 →
            // 原子读取当前凭证自愈（ReadCurrentToken.lua 内含版本守卫，不会把新登录凭证交给旧会话）
            List<String> current = stringRedisTemplate.execute(readCurrentTokenScript,
                    Arrays.asList(
                            RedisConstants.LOGIN_USER_KEY + userId,
                            RedisConstants.LOGIN_REFRESH_USER_KEY + userId,
                            RedisConstants.LOGIN_VALID_VERSION_KEY + userId
                    ),
                    accessToken, oldVersion.toString());
            if (current != null && !current.isEmpty()) {
                log.info("并发已刷新，返回当前 token userId={}", userId);
                return new TokenPair(current.get(0), current.get(1), oldVersion);
            }
            log.warn("refreshToken 不匹配且非并发刷新，拒绝 userId={}", userId);
            return null;
        }

        if (refreshCode == TokenRefreshCode.TOKEN_BEFORE_LOGIN) {
            log.warn("token 版本已被更新登录顶替，拒绝刷新 userId={}, oldVersion={}", userId, oldVersion);
            return null;
        }
        if (refreshCode == TokenRefreshCode.REFRESH_TOKEN_NOT_FOUND
                || refreshCode == TokenRefreshCode.ORIGIN_VERSION_NULL) {
            log.warn("会话不存在/版本键缺失，拒绝刷新 userId={}, code={}", userId, code);
            return null;
        }

        log.warn("未知刷新返回码 code={} userId={}", code, userId);
        return null;
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
