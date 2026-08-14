package com.hmdp.config;

import com.hmdp.interceptor.RefreshTokenInterceptor;
import com.hmdp.interceptor.LoginInterceptor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import jakarta.annotation.Resource;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Spring MVC 拦截器配置 — 登录校验拦截器 + Token自动续期间拦截器
 * <p>
 * 两拦截器共享同一份公开路径白名单（PUBLIC_NO_TOKEN），消除双斜杠笔误与清单漂移。
 * 语义：
 * <ul>
 *   <li>RefreshTokenInterceptor（order 0）：PUBLIC_NO_TOKEN 完全放行（无 token 可访问）；</li>
 *   <li>LoginInterceptor（order 1）：在 PUBLIC_NO_TOKEN 基础上，PUBLIC_NO_LOGIN_ONLY 免登录态校验
 *       （但仍需有效 token，即 RefreshTokenInterceptor 不放行）。</li>
 * </ul>
 */
@Configuration
public class MvcConfig implements WebMvcConfigurer {
    @Resource
    private RefreshTokenInterceptor refreshTokenInterceptor;

    /** 完全公开接口：无需 token（RefreshTokenInterceptor 放行） */
    private static final String[] PUBLIC_NO_TOKEN = {
            "/user/login", "/user/code", "/user/password/reset",
            "/shop/**", "/voucher/**", "/shop-type/**", "/blog/hot",
            "/test/restart/**", "/imgs/**", "/error", "/"
    };

    /** 仅免登录态校验，但仍需有效 token（RefreshTokenInterceptor 不放行） */
    private static final String[] PUBLIC_NO_LOGIN_ONLY = {
            "/upload"
    };

    /** Swagger/OpenAPI 文档路径（两拦截器均放行） */
    private static final String[] SWAGGER_PATHS = {
            "/swagger-ui/**", "/swagger-ui.html", "/v3/api-docs/**",
            "/swagger-resources/**", "/webjars/**", "/doc.html"
    };

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(refreshTokenInterceptor)
                .addPathPatterns("/**")
                .excludePathPatterns(concat(PUBLIC_NO_TOKEN, SWAGGER_PATHS))
                .order(0);
        registry.addInterceptor(new LoginInterceptor())
                .excludePathPatterns(concat(PUBLIC_NO_TOKEN, PUBLIC_NO_LOGIN_ONLY, SWAGGER_PATHS))
                .order(1);
    }

    private static String[] concat(String[]... arrays) {
        List<String> all = new ArrayList<>();
        for (String[] a : arrays) {
            all.addAll(Arrays.asList(a));
        }
        return all.toArray(new String[0]);
    }
}
