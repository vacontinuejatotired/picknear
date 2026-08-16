package com.hmdp.auth.login;

import com.hmdp.auth.dto.LoginFormDTO;
import com.hmdp.auth.dto.TokenPair;
import com.hmdp.auth.token.TokenService;
import com.hmdp.auth.verifycode.VerifyCodeService;
import com.hmdp.user.account.UserAccountService;
import com.hmdp.user.entity.User;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import jakarta.annotation.Resource;

/**
 * 验证码登录策略 — 原子消费验证码 + 自动注册
 * <p>
 * 自 UserServiceImpl.loginByCode 迁出（P2-S5），行为等价：
 * 验证码消费失败即抛错；用户不存在则自动创建（无密码）。
 * </p>
 */
@Slf4j
@Component
public class CodeLoginStrategy implements LoginStrategy {

    @Resource
    private VerifyCodeService verifyCodeService;
    @Resource
    private UserAccountService userAccountService;
    @Resource
    private TokenService tokenService;

    @Override
    public boolean supports(LoginFormDTO form) {
        return form.getPassword() == null;
    }

    @Override
    public TokenPair login(LoginFormDTO form) {
        String phone = form.getPhone();
        String code = form.getCode();

        // 原子消费验证码
        if (!verifyCodeService.consumeVerifyCode(phone, code)) {
            log.info("验证码错误 phone={}, code={}", phone, code);
            throw new IllegalArgumentException("验证码错误");
        }

        // 查用户 → 不存在则自动创建
        User user = userAccountService.queryByPhone(phone);
        if (user == null) {
            user = userAccountService.createUser(phone, null);
        }

        // 生成 Token
        log.info("【验证码登录成功】userId={}", user.getId());
        return tokenService.generateTokenPair(user.getId());
    }
}
