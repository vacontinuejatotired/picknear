package com.hmdp.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Cookie 配置 — 从 application-{profile}.yaml 读取 app.cookie 配置
 * <p>
 * dev:  same-site=Lax, secure=false
 * prod: same-site=None, secure=true
 */
@Data
@Component
@ConfigurationProperties(prefix = "app.cookie")
public class CookieConfig {
    /** SameSite 属性值: Lax（开发）/ None（生产） */
    private String sameSite = "Lax";
    /** Secure 属性: false（开发）/ true（生产） */
    private boolean secure = false;
}
