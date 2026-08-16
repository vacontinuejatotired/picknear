package com.hmdp.auth.service.impl;

import com.hmdp.auth.dto.TokenPair;
import com.hmdp.auth.dto.ValidationResult;
import com.hmdp.auth.service.AuthService;
import com.hmdp.auth.session.SessionContextService;
import com.hmdp.auth.token.TokenService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import jakarta.annotation.Resource;

/**
 * 认证服务实现 — 纯门面（P2-S3 后形态）：
 * Token 生命周期委托 {@link TokenService}，会话上下文/登出委托
 * {@link SessionContextService}，验证码已归 {@code VerifyCodeService}。
 * <p>
 * 保留接口兼容（RefreshTokenInterceptor / UserServiceImpl 仍注入 AuthService）；
 * P2-S6 收尾时删除本类，调用方改注入具体组件。
 * </p>
 */
@Slf4j
@Service
public class AuthServiceImpl implements AuthService {

    @Resource
    private TokenService tokenService;
    @Resource
    private SessionContextService sessionContextService;

    // ==================== Token 生命周期（委托 TokenService） ====================

    @Override
    public TokenPair generateTokenPair(Long userId) {
        return tokenService.generateTokenPair(userId);
    }

    @Override
    public ValidationResult validateAccessToken(String token) {
        return tokenService.validateAccessToken(token);
    }

    @Override
    public TokenPair refreshTokenPair(String accessToken, String refreshToken,
                                      Long userId, Long oldVersion, boolean isExpired) {
        return tokenService.refreshTokenPair(accessToken, refreshToken, userId, oldVersion, isExpired);
    }

    @Override
    public TokenRefreshResult refreshTokenPairWithLock(String accessToken, String refreshToken,
                                                       Long userId, Long oldVersion, boolean isExpired) {
        return tokenService.refreshTokenPairWithLock(accessToken, refreshToken, userId, oldVersion, isExpired);
    }

    @Override
    public boolean isSessionSuperseded(Long userId, Long version) {
        return tokenService.isSessionSuperseded(userId, version);
    }

    // ==================== 会话上下文 / 登出（委托 SessionContextService） ====================

    @Override
    public boolean saveUserToContext(Long userId) {
        return sessionContextService.saveUserToContext(userId);
    }

    @Override
    public void revokeTokens(Long userId) {
        sessionContextService.revokeTokens(userId);
    }
}
