package com.hmdp.enums;

import lombok.Getter;

/**
 * Token 刷新结果枚举 — 定义刷新成功/失败的各种状态码
 */
@Getter
public enum TokenRefreshCode {

    // 成功
    SUCCESS(200L, "Token刷新成功"),

    // Token相关错误 (40x)
    TOKEN_KEY_NOT_FOUND(401L, "Token不存在或已过期"),
    TOKEN_MISMATCH(402L, "Token不匹配"),

    // 版本相关错误 (41x)
    VERSION_KEY_NOT_FOUND(411L, "版本号不存在"),
    TOKEN_VERSION_EXPIRED(412L, "Token版本已过期"),

    // RefreshToken相关错误 (42x)
    REFRESH_TOKEN_NOT_FOUND(421L, "RefreshToken不存在"),
    REFRESH_TOKEN_MISMATCH(422L, "RefreshToken不匹配"),
    //userInfo
    // 版本相关错误新增 (413)
    /**
     * 版本号存在但比登录时间早，token不可使用
     * 对应Lua: if tonumber(orginVersion) > version then return 2
     */
    TOKEN_BEFORE_LOGIN(413L, "Token版本早于登录时间，不可使用"),

    // 通用错误新增 (431)
    /**
     * 原始版本号为null
     * 对应Lua: if not orginVersion then return 0
     */
    ORIGIN_VERSION_NULL(431L, "原始版本号不存在"),
    /**
     * 44x
     * userInfo 相关错误
     */
    USEINFO_NOT_FOUND (441L,"用户信息不存在"),
    USERINFO_CACHE_EMPTY (442L,"用户信息缓存空值");


    private final Long code;  // Long 类型
    private final String message;

    TokenRefreshCode(Long code, String message) {
        this.code = code;
        this.message = message;
    }

    /**
     * 根据状态码获取枚举
     */
    public static TokenRefreshCode fromCode(Long code) {  // int → Long
        if (code == null) {
            return null;
        }
        for (TokenRefreshCode value : values()) {
            if (value.code.equals(code)) {  // == → equals()
                return value;
            }
        }
        return null;
    }

    /**
     * 判断是否成功
     */
    public boolean isSuccess() {
        return this == SUCCESS;
    }

    /**
     * 获取默认错误消息
     */
    public static String getDefaultMessage(Long code) {  // int → Long
        TokenRefreshCode enumCode = fromCode(code);
        return enumCode != null ? enumCode.getMessage() : "未知错误：" + code;
    }
}