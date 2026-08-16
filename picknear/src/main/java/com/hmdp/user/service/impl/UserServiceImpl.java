package com.hmdp.user.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.auth.dto.LoginFormDTO;
import com.hmdp.auth.dto.PasswordChangeDTO;
import com.hmdp.auth.dto.TokenPair;
import com.hmdp.auth.login.LoginStrategyRegistry;
import com.hmdp.auth.password.PasswordService;
import com.hmdp.auth.session.SessionContextService;
import com.hmdp.auth.verifycode.VerifyCodeService;
import com.hmdp.dto.Result;
import com.hmdp.user.dto.ProfileUpdateDTO;
import com.hmdp.user.entity.User;
import com.hmdp.user.mapper.UserMapper;
import com.hmdp.user.profile.ProfileService;
import com.hmdp.user.service.IUserService;
import com.hmdp.utils.RegexUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import jakarta.annotation.Resource;

/**
 * 用户服务实现 — 登录入口（策略路由）+ 认证/资料委托
 * <p>
 * 职责边界（2026-08 收尾后）：
 * <ul>
 *   <li>登录：LoginStrategyRegistry 策略路由（密码/验证码，auth.login）</li>
 *   <li>委托：验证码 / 改密 / 重置 / 登出（VerifyCodeService / PasswordService / SessionContextService）</li>
 *   <li>资料：updateProfile 委托 ProfileService（消除与 user.profile 的重复实现）</li>
 * </ul>
 * </p>
 */
@Slf4j
@Service
public class UserServiceImpl extends ServiceImpl<UserMapper, User> implements IUserService {

    @Resource
    private LoginStrategyRegistry loginStrategyRegistry;
    @Resource
    private SessionContextService sessionContextService;
    @Resource
    private VerifyCodeService verifyCodeService;
    @Resource
    private PasswordService passwordService;
    @Resource
    private ProfileService profileService;

    @Override
    public TokenPair login(LoginFormDTO loginForm) {
        // ① 手机号格式校验
        if (RegexUtils.isPhoneInvalid(loginForm.getPhone())) {
            throw new IllegalArgumentException("手机号不规范");
        }
        // ② 策略路由：密码登录（password 非空）/ 验证码登录，supports 自判定
        return loginStrategyRegistry.resolve(loginForm).login(loginForm);
    }

    @Override
    public void logout(Long userId) {
        sessionContextService.revokeTokens(userId);
    }

    @Override
    public Result updateProfile(ProfileUpdateDTO dto) {
        return profileService.updateProfile(dto);
    }

    @Override
    public Result resetPassword(String phone, String code, String newPassword) {
        return passwordService.resetPassword(phone, code, newPassword);
    }

    @Override
    public Result sendCode(String phone) {
        return verifyCodeService.sendCode(phone);
    }

    @Override
    public TokenPair changePassword(PasswordChangeDTO dto) {
        return passwordService.changePassword(dto);
    }
}
