package com.hmdp.interceptor;

import com.hmdp.utils.UserHolder;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.servlet.HandlerInterceptor;
/**
 * 登录校验拦截器 — 检查 UserHolder 中是否有用户ID，无则返回401
 * 仅拦截需要登录的接口
 */
@Slf4j
public class LoginInterceptor implements HandlerInterceptor {


    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        String requestURI = request.getRequestURI();
        String method = request.getMethod();
        String authHeader = request.getHeader("authorization");
        String refreshHeader = request.getHeader("Refresh-Token");

        if (UserHolder.getUserDTO() == null) {
            log.warn("【登录拦截】请求被拒绝401, URI={} {}, authorization头={}, Refresh-Token头={}",
                    method, requestURI,
                    authHeader != null ? maskToken(authHeader) : "null",
                    refreshHeader != null ? maskToken(refreshHeader) : "null");
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            return false;
        }

        log.info("【登录拦截】请求放行, URI={} {}, userId={}", method, requestURI, UserHolder.getUserId());
        return true;
    }

    /**
     * 对令牌进行脱敏处理，只显示前10位和后10位，避免敏感信息泄露
     */
    private String maskToken(String token) {
        if (token == null) return null;
        if (token.length() <= 20) return token;
        return token.substring(0, 10) + "..." + token.substring(token.length() - 10);
    }


}
