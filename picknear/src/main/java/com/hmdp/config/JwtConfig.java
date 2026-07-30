package com.hmdp.config;

import com.hmdp.utils.security.JwtUtil;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.DefaultResourceLoader;

/**
 * JWT 配置 — 读取 RSA 密钥对，提供签名/验签能力
 */
@Configuration
public class JwtConfig {

    @Bean
    public JwtUtil jwtUtil(DefaultResourceLoader defaultResourceLoader) {

        JwtUtil jwtUtil = new JwtUtil(defaultResourceLoader);
        return jwtUtil;
    }
}
