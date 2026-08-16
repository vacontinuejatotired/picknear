package com.hmdp.auth.service.impl;

import com.github.benmanes.caffeine.cache.LoadingCache;
import com.hmdp.auth.dto.TokenPair;
import com.hmdp.auth.dto.ValidationResult;
import com.hmdp.auth.service.AuthService;
import com.hmdp.auth.token.TokenService;
import com.hmdp.user.entity.UserInfo;
import com.hmdp.user.entity.UserinfoCache;
import com.hmdp.user.service.IUserInfoService;
import com.hmdp.utils.UserHolder;
import com.hmdp.utils.cache.CaffeineConstants;
import com.hmdp.utils.redis.RedisConstants;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import jakarta.annotation.Resource;

/**
 * 认证服务实现 — Token 生命周期已下沉 {@link TokenService}（P2-S2），
 * 本类仅保留用户上下文注入与登出清理（P2-S3 迁至 SessionContextService 后删除）。
 * <p>
 * 顺带清理：BatchLoadCache 死注入（原 :46-47，从未使用）。
 * </p>
 */
@Slf4j
@Service
public class AuthServiceImpl implements AuthService {

    @Resource
    private TokenService tokenService;
    @Resource
    private StringRedisTemplate stringRedisTemplate;
    @Resource(name = "userinfoCache")
    private LoadingCache<String, UserinfoCache> userinfoCaffeine;
    @Resource
    private IUserInfoService userInfoService;

    // ==================== Token 生命周期（委托 TokenService） ====================

    @Override
    public TokenPair generateTokenPair(Long userId) {
        return tokenService.generateTokenPair(userId);
    }

    @Override
    public ValidationResult validateAccessToken(String token) {
        return tokenService.validateAccessToken(token);
    }

    @Override
    public TokenPair refreshTokenPair(String accessToken, String refreshToken,
                                      Long userId, Long oldVersion, boolean isExpired) {
        return tokenService.refreshTokenPair(accessToken, refreshToken, userId, oldVersion, isExpired);
    }

    @Override
    public TokenRefreshResult refreshTokenPairWithLock(String accessToken, String refreshToken,
                                                       Long userId, Long oldVersion, boolean isExpired) {
        return tokenService.refreshTokenPairWithLock(accessToken, refreshToken, userId, oldVersion, isExpired);
    }

    @Override
    public boolean isSessionSuperseded(Long userId, Long version) {
        return tokenService.isSessionSuperseded(userId, version);
    }

    // ==================== 用户上下文 / 登出 ====================

    @Override
    public boolean saveUserToContext(Long userId) {
        try {
            UserHolder.saveUserId(userId);
            String userInfoKey = CaffeineConstants.USERINFO_CACHE_KEY + userId;
            UserinfoCache cache = userinfoCaffeine.get(userInfoKey);
            // Caffeine load 返回空值时不阻塞等待异步加载
            // 兜底：nickName 或 icon 为空时从 DB 同步回填
            if (cache.getNickName() == null || cache.getNickName().isEmpty()
                    || cache.getIcon() == null || cache.getIcon().isEmpty()) {
                UserInfo userInfo = userInfoService.getById(userId);
                if (userInfo != null) {
                    cache = new UserinfoCache(userId, userInfo.getNickName(), userInfo.getIcon());
                    userinfoCaffeine.put(userInfoKey, cache);
                }
            }
            UserHolder.saveUserDTO(cache);
            return true;
        } catch (Exception e) {
            log.error("Failed to save to ThreadLocal for userId: {}", userId, e);
            return false;
        }
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
        tokenService.invalidateLocalVersionCache(userId);
        log.info("【登出】已清除 userId={} 的所有 Token、Version 和本地缓存", userId);
    }
}
