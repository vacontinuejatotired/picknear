package com.hmdp.utils.security;

/**
 * Cookie 构建工具 — Refresh Token httpOnly Cookie 的统一出口。
 * <p>
 * 拦截器（Token 刷新写回）与 UserController（登录/改密写回）原先各自拼 Set-Cookie 字符串，
 * 收敛到此处避免策略漂移。SameSite/Secure 按连接是否 HTTPS 动态判断：
 * HTTPS → SameSite=None + Secure；HTTP → SameSite=Lax 不加 Secure
 * （避免浏览器因 Secure 标志拒绝 HTTP cookie；跨源 HTTPS 场景需 None+Secure）。
 * </p>
 */
public final class CookieWriter {

    /** Refresh Token Cookie 名 */
    public static final String REFRESH_TOKEN_COOKIE_NAME = "refresh_token";

    /** Refresh Token 有效期（秒）：7 天 */
    public static final long REFRESH_TOKEN_MAX_AGE_SECONDS = 7 * 24 * 60 * 60;

    private CookieWriter() {}

    /**
     * 构建 Refresh Token 的 Set-Cookie 值。
     *
     * @param refreshToken token 值
     * @param isSecure     当前连接是否 HTTPS（决定 Secure + SameSite=None）
     */
    public static String refreshTokenCookie(String refreshToken, boolean isSecure) {
        String sameSite = isSecure ? "None" : "Lax";
        return String.format(
                "%s=%s; HttpOnly; %sSameSite=%s; Path=/; MaxAge=%d",
                REFRESH_TOKEN_COOKIE_NAME, refreshToken,
                isSecure ? "Secure; " : "",
                sameSite,
                REFRESH_TOKEN_MAX_AGE_SECONDS
        );
    }
}
