package com.hmdp.service;

import com.hmdp.dto.TokenPair;
import com.hmdp.dto.UserDTO;
import com.hmdp.dto.ValidationResult;

/**
 * 认证服务 — Token 生成/校验/刷新/注销，纯业务逻辑，无 HTTP 依赖。
 * <p>
 * 职责边界：
 * <ul>
 *   <li>生成双 Token + version（generateTokenPair）</li>
 *   <li>JWT 解析 + 两级版本校验（validateAccessToken）</li>
 *   <li>临期/过期刷新（refreshTokenPair）</li>
 *   <li>登出吊销（revokeTokens）</li>
 *   <li>验证码原子消费（consumeVerifyCode）</li>
 *   <li>用户信息缓存（cacheUserInfo）</li>
 * </ul>
 * Controller 和 Interceptor 只负责 HTTP 读写（响应头/请求头），不关心 Token 如何生成。
 */
public interface AuthService {

    /** 登录：生成双 Token + version */
    TokenPair generateTokenPair(Long userId);

    /** 校验 access token：JWT 解析 → Caffeine 快速拒绝 → Redis 最终校验 */
    ValidationResult validateAccessToken(String token);

    /**
     * 刷新 Token 对 — 处理临期刷新和过期刷新两种场景
     * @param accessToken 当前 access token
     * @param refreshToken 请求携带的 refresh token
     * @param userId 用户 ID
     * @param oldVersion 当前版本号（来自 token claims）
     * @param isExpired true=JWT 已过期（生成新版本+新 refreshToken），false=临期（保持版本）
     * @return 新的 TokenPair，刷新失败返回 null
     */
    TokenPair refreshTokenPair(String accessToken, String refreshToken, Long userId, Long oldVersion, boolean isExpired);

    /** 登出：删除 Redis 中该用户的所有 Token/Version */
    void revokeTokens(Long userId);

    /** 原子消费验证码：GET + DEL，防止重放 */
    boolean consumeVerifyCode(String phone, String code);
}
