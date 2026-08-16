package com.hmdp.auth.session;

import com.github.benmanes.caffeine.cache.LoadingCache;
import com.hmdp.auth.token.TokenService;
import com.hmdp.user.entity.UserInfo;
import com.hmdp.user.entity.UserinfoCache;
import com.hmdp.user.service.IUserInfoService;
import com.hmdp.utils.UserHolder;
import com.hmdp.utils.cache.CaffeineConstants;
import com.hmdp.utils.redis.RedisConstants;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import jakarta.annotation.Resource;

/**
 * 会话上下文服务 — 用户上下文注入与登出清理（auth 域收敛，P2-S3）
 * <p>
 * 职责边界（自 AuthServiceImpl 迁出，行为等价）：
 * <ul>
 *   <li>saveUserToContext：Caffeine 缓存（异步填充）→ nickName/icon 为空时 DB
 *       同步回填 → UserHolder（供拦截器每次请求通过校验后调用）</li>
 *   <li>revokeTokens：删除 Redis 4 个 Token/Version 键 + 清除本地缓存
 *       （userinfoCaffeine + tokenValidVersionCache）</li>
 * </ul>
 * </p>
 */
@Slf4j
@Component
public class SessionContextService {

    @Resource
    private StringRedisTemplate stringRedisTemplate;
    @Resource(name = "userinfoCache")
    private LoadingCache<String, UserinfoCache> userinfoCaffeine;
    @Resource
    private IUserInfoService userInfoService;
    @Resource
    private TokenService tokenService;

    /**
     * 加载用户信息到当前线程上下文：Caffeine 缓存（异步填充）→ nickName/icon 为空时 DB 同步回填 → UserHolder。
     * 失败（缓存/DB 异常）返回 false，调用方按未登录处理。
     */
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

    /** 登出：删除 Redis 中该用户的所有 Token/Version，并清除本地 Caffeine 缓存 */
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
