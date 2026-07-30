package com.hmdp.config;

import com.hmdp.utils.redis.LockFreeRedisScript;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.script.DefaultRedisScript;

import java.io.IOException;

/**
 * Redis Lua 脚本配置 — 预加载所有 Lua 脚本，避免运行时懒加载
 */
@Configuration
@Slf4j
public class RedisLuaConfig {

    /**
     * 通用Lua脚本创建方法
     */
    private <T> DefaultRedisScript<T> createScript(String path, Class<T> resultType) {

        ClassPathResource resource = new ClassPathResource(path);
        String scriptContent = null;
        try {
            scriptContent = new String(resource.getInputStream().readAllBytes());
        } catch (IOException e) {
            log.error("加载Lua脚本 [{}] 失败: {}", path, e.getMessage());
            throw new RuntimeException("加载Lua脚本失败", e);
        }
        LockFreeRedisScript script = new LockFreeRedisScript(scriptContent, resultType);
        log.debug("脚本SHA1: {}", script.getSha1());
        return script;
    }

    @Bean(name = "seckillScript")
    public DefaultRedisScript<Long> seckillScript() {
        return createScript("MqSeckill.lua", Long.class);
    }

    @Bean(name = "refreshDeadlineTokenScript")
    public DefaultRedisScript<Long> refreshTokenScript() {
        return createScript("RefreshToken.lua", Long.class);
    }

    @Bean(name = "refreshDeadTokenScript")
    public DefaultRedisScript<Long> refreshTokenScript2() {
        return createScript("RefreshExpiredToken.lua", Long.class);
    }

    @Bean(name = "consumeVerifyCodeScript")
    public DefaultRedisScript<String> consumeVerifyCodeScript() {
        return createScript("ConsumeVerifyCode.lua", String.class);
    }

    @Bean(name = "REDIS_LOGIN_SET_TOKEN")
    public DefaultRedisScript<String> loginSetTokenScript() {
        return createScript("LoginSetToken.lua", String.class);
    }

}