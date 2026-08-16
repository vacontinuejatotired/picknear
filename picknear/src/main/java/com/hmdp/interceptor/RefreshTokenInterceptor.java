package com.hmdp.interceptor;

import com.hmdp.auth.dto.TokenPair;
import com.hmdp.auth.dto.ValidationResult;
import com.hmdp.auth.service.AuthService;
import com.hmdp.auth.service.AuthService.TokenRefreshResult;
import com.hmdp.utils.UserHolder;
import com.hmdp.utils.security.CookieWriter;
import jakarta.annotation.Resource;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * Token 自动续期间拦截器 — 校验/刷新/用户上下文全权委托 AuthService，仅处理 HTTP 细节。
 * 优先级高于 LoginInterceptor，拦截所有请求（除公开接口）
 */
@Slf4j
@Component
public class RefreshTokenInterceptor implements HandlerInterceptor {

    @Resource
    private AuthService authService;

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

            // Refresh Token 双通道读取：前端显式携带的请求头优先，httpOnly Cookie 兜底
            // （跨 host / 浏览器清 cookie 时请求头仍能送达，规避"refreshToken is null"掉登录）
            String refreshToken = readRefreshToken(request);

            // ① AuthService 做完整校验：JWT 解析 + Caffeine + Redis 版本
            ValidationResult result = authService.validateAccessToken(token);

            if (!result.isValid() && !result.isNeedsRefresh()) {
                // JWT 无效（签名错误、格式错误等）或会话被更新登录顶替（版本校验不过）。
                // 后者是"被顶替"最常见路径：AT 未过期、但 validVersion 已被新登录顶高。
                boolean superseded = authService.isSessionSuperseded(result.getUserId(), result.getVersion());
                writeUnauthorized(response, superseded);
                log.warn("【Token拦截】JWT校验失败或会话被顶替, URI={}, superseded={}", requestURI, superseded);
                return false;
            }

            // ② 保存用户信息到 ThreadLocal（Caffeine 缓存 → DB 兜底回填）
            Long userId = result.getUserId();
            if (userId == null) {
                log.warn("【Token拦截】无法获取 userId, URI={}", requestURI);
                response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
                return false;
            }
            if (!authService.saveUserToContext(userId)) {
                response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
                return false;
            }

            // ③ 无需刷新 → 放行
            if (!result.isNeedsRefresh()) {
                log.info("【Token拦截】Token 有效 userId={}, 放行", userId);
                return true;
            }

            // ④ 需要刷新 — 带锁刷新委托 AuthService（锁的获取/释放内聚服务层）
            log.info("【Token拦截】Token 需要刷新 userId={}", userId);
            TokenRefreshResult refreshResult = authService.refreshTokenPairWithLock(
                    token, refreshToken, userId, result.getVersion(), !result.isValid());

            switch (refreshResult.status()) {
                case OK -> {
                    // 刷新成功：写回响应头 + 设置 Refresh Token Cookie
                    TokenPair newPair = refreshResult.tokenPair();
                    response.setHeader("X-Token-Refresh", "ok");
                    response.setHeader("authorization", "Bearer " + newPair.getAccessToken());
                    // 双通道：响应头让前端刷新 localStorage 中的 RT，与 Set-Cookie 保持同步
                    response.setHeader("Refresh-Token", newPair.getRefreshToken());
                    if (newPair.getRefreshToken() != null) {
                        response.addHeader("Set-Cookie",
                                CookieWriter.refreshTokenCookie(newPair.getRefreshToken(), request.isSecure()));
                    }
                    log.info("【Token拦截】刷新成功 userId={}", userId);
                    return true;
                }
                case SKIPPED -> {
                    // 刷新锁被占用，跳过刷新；不写回 authorization 头，避免旧值覆盖前端已更新的新 token
                    response.setHeader("X-Token-Refresh", "skipped");
                    return true;
                }
                case FAILED -> {
                    log.warn("【Token拦截】刷新失败 userId={}", userId);
                    response.setHeader("X-Token-Refresh", "failed");
                    // 区分失败原因：被新登录顶替 vs 无有效会话，供前端决策提示文案
                    boolean superseded = authService.isSessionSuperseded(userId, result.getVersion());
                    writeUnauthorized(response, superseded);
                    return false;
                }
                default -> {
                    return false;
                }
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

    /** 读取 Refresh Token：请求头优先，httpOnly Cookie 兜底 */
    private String readRefreshToken(HttpServletRequest request) {
        String refreshToken = request.getHeader("Refresh-Token");
        if (refreshToken == null || refreshToken.isEmpty()) {
            Cookie[] cookies = request.getCookies();
            if (cookies != null) {
                for (Cookie cookie : cookies) {
                    if (CookieWriter.REFRESH_TOKEN_COOKIE_NAME.equals(cookie.getName())) {
                        refreshToken = cookie.getValue();
                        break;
                    }
                }
            }
        }
        return refreshToken;
    }

    /**
     * 401 + X-Auth-Reason 透传：superseded=账号已在其他设备登录；no-session=登录已过期/无会话。
     */
    private void writeUnauthorized(HttpServletResponse response, boolean superseded) {
        response.setHeader("X-Auth-Reason", superseded ? "superseded" : "no-session");
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) {
        UserHolder.remove();
        log.debug("用户信息已清除, URI={}", request.getRequestURI());
    }
}
