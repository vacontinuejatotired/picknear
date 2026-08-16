package com.hmdp.auth.service;

import com.hmdp.auth.dto.TokenPair;
import com.hmdp.auth.dto.ValidationResult;
import com.hmdp.dto.UserDTO;

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

    /**
     * 带分布式锁的刷新 — 同一用户并发只执行一次刷新，其余请求跳过。
     * <p>
     * 供 RefreshTokenInterceptor 在"临期/过期"路径调用，锁的获取/释放内聚在服务层。
     * </p>
     * @return OK=刷新成功（携带新 TokenPair）；SKIPPED=刷新锁被占用（调用方放行，不写回旧值）；
     *         FAILED=刷新失败（调用方 401，可用 {@link #isSessionSuperseded} 区分原因）
     */
    TokenRefreshResult refreshTokenPairWithLock(String accessToken, String refreshToken,
                                                Long userId, Long oldVersion, boolean isExpired);

    /**
     * 判定会话是否已被更新登录顶替：Redis validVersion > token 携带的 version。
     * <p>
     * 供 401 响应透传 X-Auth-Reason，前端据此区分"账号已在其他设备登录"与"登录已过期"。
     * version 为 null（签名错误等场景无版本可比）或 validVersion 缺失/非数字时一律视为未顶替。
     * </p>
     */
    boolean isSessionSuperseded(Long userId, Long version);

    /**
     * 加载用户信息到当前线程上下文：Caffeine 缓存（异步填充）→ nickName/icon 为空时 DB 同步回填 → UserHolder。
     * <p>
     * 拦截器在每次请求通过校验后调用；失败（缓存/DB 异常）返回 false，调用方按未登录处理。
     * </p>
     */
    boolean saveUserToContext(Long userId);

    /** 登出：删除 Redis 中该用户的所有 Token/Version */
    void revokeTokens(Long userId);

    /** 原子消费验证码：GET + DEL，防止重放 */
    boolean consumeVerifyCode(String phone, String code);

    /**
     * 带锁刷新结果：OK=成功（tokenPair 非空）；SKIPPED=锁被占用，调用方跳过刷新直接放行；
     * FAILED=刷新失败（tokenPair 为 null）。
     */
    record TokenRefreshResult(TokenPair tokenPair, TokenRefreshStatus status) {

        public static TokenRefreshResult ok(TokenPair tokenPair) {
            return new TokenRefreshResult(tokenPair, TokenRefreshStatus.OK);
        }

        public static TokenRefreshResult skipped() {
            return new TokenRefreshResult(null, TokenRefreshStatus.SKIPPED);
        }

        public static TokenRefreshResult failed() {
            return new TokenRefreshResult(null, TokenRefreshStatus.FAILED);
        }

        public enum TokenRefreshStatus { OK, SKIPPED, FAILED }
    }
}
