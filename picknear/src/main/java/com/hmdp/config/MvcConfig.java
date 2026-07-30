package com.hmdp.config;

import com.hmdp.interceptor.RefreshTokenInterceptor;
import com.hmdp.interceptor.LoginInterceptor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import jakarta.annotation.Resource;

/**
 * Spring MVC 拦截器配置 — 登录校验拦截器 + Token自动续期间拦截器
 */
@Configuration
public class MvcConfig implements WebMvcConfigurer {
    @Resource
    private RefreshTokenInterceptor refreshTokenInterceptor;
    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new LoginInterceptor())
                .excludePathPatterns(
                        "/user/login","/shop/**","/voucher/**","/blog/hot",
                        "/upload","/user/code","//user/code","/user/password/reset","//user/password/reset",
                        "/shop-type/**","/test/restart/**",
                        "/imgs/**","/error","/",
                        "/swagger-ui/**", "/swagger-ui.html", "/v3/api-docs/**", "/swagger-resources/**",
                        "/webjars/**", "/doc.html").order(1);
        registry.addInterceptor(refreshTokenInterceptor).addPathPatterns("/**").excludePathPatterns(
                "/blog/hot","/user/login","//user/login","/user/code","//user/code","/user/password/reset","//user/password/reset","/shop-type/list",
                "/test/restart/**", "/shop/**","/voucher/**","/imgs/**","/error","/",
                "/swagger-ui/**", "/swagger-ui.html", "/v3/api-docs/**", "/swagger-resources/**",
                "/webjars/**", "/doc.html").order(0);
    }
}
