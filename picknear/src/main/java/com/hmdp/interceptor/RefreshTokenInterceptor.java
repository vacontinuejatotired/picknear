package com.hmdp.interceptor;

import com.github.benmanes.caffeine.cache.LoadingCache;
import com.hmdp.dto.TokenPair;
import com.hmdp.dto.ValidationResult;
import com.hmdp.entity.UserinfoCache;
import com.hmdp.entity.UserInfo;
import com.hmdp.service.AuthService;
import com.hmdp.service.IUserInfoService;
import com.hmdp.utils.UserHolder;
import com.hmdp.utils.cache.CaffeineConstants;
import com.hmdp.utils.redis.RedisConstants;
import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.concurrent.TimeUnit;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * Token 自动续期间拦截器 — 校验 + 刷新全权委托 AuthService，仅处理 HTTP 细节
 * 优先级高于 LoginInterceptor，拦截所有请求（除公开接口）
 */
@Slf4j
@Component
public class RefreshTokenInterceptor implements HandlerInterceptor {

    @Resource
    private AuthService authService;
    @Resource
    private StringRedisTemplate stringRedisTemplate;
    @Resource(name = "userinfoCache")
    private LoadingCache<String, UserinfoCache> userinfoCaffeine;
    @Resource
    private IUserInfoService userInfoService;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        long methodStartTime = System.currentTimeMillis();
        String requestURI = request.getRequestURI();
        String method = request.getMethod();

        try {
            log.info("【Token拦截】请求路径 {} {}", method, requestURI);

            if (!checkTokenHeader(request, response)) {
                log.warn("【Token拦截】缺少authorization请求头, URI={} {}", method, requestURI);
                return false;
            }
// 统一全小写 authorization，getHeader 大小写不敏感无需 fallback
            String token = request.getHeader("authorization");
            if (token != null && token.startsWith("Bearer ")) {
                token = token.substring(7);
            }

            // Refresh Token 从 httpOnly Cookie 读取，JS 不可访问
            String refreshToken = null;
            jakarta.servlet.http.Cookie[] cookies = request.getCookies();
            if (cookies != null) {
                for (jakarta.servlet.http.Cookie cookie : cookies) {
                    if ("refresh_token".equals(cookie.getName())) {
                        refreshToken = cookie.getValue();
                        break;
                    }
                }
            }

            // ① AuthService 做完整校验：JWT 解析 + Caffeine + Redis 版本
            ValidationResult result = authService.validateAccessToken(token);

            if (!result.isValid() && !result.isNeedsRefresh()) {
                // JWT 无效（签名错误、格式错误等）或会话被更新登录顶替（版本校验不过）。
                // 后者是"被顶替"最常见路径：AT 未过期、但 validVersion 已被新登录顶高。
                boolean superseded = isSuperseded(result.getUserId(), result.getVersion());
                response.setHeader("X-Auth-Reason", superseded ? "superseded" : "no-session");
                log.warn("【Token拦截】JWT校验失败或会话被顶替, URI={}, superseded={}", requestURI, superseded);
                response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
                return false;
            }

            // ② 保存用户信息到 ThreadLocal
            Long userId = result.getUserId();

            if (userId == null) {
                log.warn("【Token拦截】无法获取 userId, URI={}", requestURI);
                response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
                return false;
            }
            
            if (!resolveAndSaveUser(userId)) {
                response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
                return false;
            }

            // ③ 无需刷新 → 放行
            if (!result.isNeedsRefresh()) {
                log.info("【Token拦截】Token 有效 userId={}, 放行", userId);
                return true;
            }

            // ④ 需要刷新 — 分布式锁 + 委托 AuthService
            log.info("【Token拦截】Token 需要刷新 userId={}", userId);
            boolean isExpired = !result.isValid();

            // 分布式锁保护：同一用户同时只有一个刷新请求执行
            String lockKey = "lock:refresh:" + userId;
            boolean locked = Boolean.TRUE.equals(stringRedisTemplate.opsForValue()
                    .setIfAbsent(lockKey, "1", 3, TimeUnit.SECONDS));
            if (!locked) {
                log.info("【Token拦截】刷新锁被占用，跳过刷新 userId={}", userId);
                response.setHeader("X-Token-Refresh", "skipped");
                // 不写回 authorization 头，避免旧值覆盖前端已更新的新 token
                return true;
            }
            try {
                if(result.getVersion() == null) {
                    log.warn("【Token拦截】无法获取 version, result={}", result);
                    response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
                    return false;
                }
                TokenPair newPair = authService.refreshTokenPair(
                        token, refreshToken, userId, result.getVersion(), isExpired);

                if (newPair == null) {
                    log.warn("【Token拦截】刷新失败 userId={}", userId);
                    response.setHeader("X-Token-Refresh", "failed");
                    // 区分失败原因：被新登录顶替 vs 无有效会话，供前端决策提示文案
                    boolean superseded = isSuperseded(userId, result.getVersion());
                    response.setHeader("X-Auth-Reason", superseded ? "superseded" : "no-session");
                    response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
                    return false;
                }

                // 刷新成功：写回响应头 + 设置 Refresh Token Cookie
                response.setHeader("X-Token-Refresh", "ok");
                response.setHeader("authorization", "Bearer " + newPair.getAccessToken());
                if (newPair.getRefreshToken() != null) {
                    boolean isSecure = request.isSecure();
                    String sameSite = isSecure ? "None" : "Lax";
                    response.addHeader("Set-Cookie", String.format(
                            "refresh_token=%s; HttpOnly; %sSameSite=%s; Path=/; MaxAge=%d",
                            newPair.getRefreshToken(),
                            isSecure ? "Secure; " : "",
                            sameSite,
                            7 * 24 * 60 * 60
                    ));
                }
                log.info("【Token拦截】刷新成功 userId={}", userId);
                return true;
            } finally {
                stringRedisTemplate.delete(lockKey);
            }

        } finally {
            long totalTime = System.currentTimeMillis() - methodStartTime;
            if (totalTime > 100) {
                log.warn("【性能告警】preHandle处理耗时过长: {} ms, URI: {} {}", totalTime, method, requestURI);
            }
        }
    }

    private boolean checkTokenHeader(HttpServletRequest request, HttpServletResponse response) {
        String token = request.getHeader("authorization");
        if (token == null) {
            log.info("token is null, URI={}", request.getRequestURI());
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            return false;
        }
        return true;
    }

    /**
     * 判定会话是否已被更新登录顶替：Redis validVersion > token 携带的 version。
     * 供 401 响应透传 X-Auth-Reason，前端据此区分"账号已在其他设备登录"与"登录已过期"。
     * version 为 null（签名错误等场景无版本可比）或 validVersion 缺失/非数字时一律视为未顶替。
     */
    private boolean isSuperseded(Long userId, Long version) {
        if (version == null) {
            return false;
        }
        String validVersion = stringRedisTemplate.opsForValue()
                .get(RedisConstants.LOGIN_VALID_VERSION_KEY + userId);
        if (validVersion == null) {
            return false;
        }
        try {
            return Long.parseLong(validVersion) > version;
        } catch (NumberFormatException e) {
            log.warn("validVersion 非数字 userId={}, validVersion={}", userId, validVersion);
            return false;
        }
    }

    /**
     * 从 Caffeine 用户缓存中加载用户信息并存入 ThreadLocal
     */
    private boolean resolveAndSaveUser(Long userId) {
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

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) {
        UserHolder.remove();
        log.debug("用户信息已清除, URI={}", request.getRequestURI());
    }
}
