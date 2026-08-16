package com.hmdp.auth.password;

import com.hmdp.auth.dto.PasswordChangeDTO;
import com.hmdp.auth.dto.TokenPair;
import com.hmdp.auth.token.TokenService;
import com.hmdp.auth.verifycode.VerifyCodeService;
import com.hmdp.dto.Result;
import com.hmdp.enums.ErrorCode;
import com.hmdp.user.account.UserAccountService;
import com.hmdp.user.dto.UserDTO;
import com.hmdp.user.entity.User;
import com.hmdp.utils.RegexUtils;
import com.hmdp.utils.UserHolder;
import com.hmdp.utils.security.PasswordEncoder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import jakarta.annotation.Resource;

/**
 * 密码服务 — 修改密码/重置密码（auth 域收敛，P2-S4）
 * <p>
 * 职责边界（自 UserServiceImpl 迁出，行为等价）：
 * <ul>
 *   <li>changePassword：校验旧密码 → BCrypt 更新 → 生成全新双 Token（bump version，旧 Token 失效）</li>
 *   <li>resetPassword：验证码消费 → 密码强度校验 → 更新密码（免旧密码）</li>
 * </ul>
 * 账号读写经 {@link UserAccountService}（user 域，P2-S5 切换）。
 * </p>
 */
@Slf4j
@Component
public class PasswordService {

    @Resource
    private UserAccountService userAccountService;
    @Resource
    private VerifyCodeService verifyCodeService;
    @Resource
    private TokenService tokenService;

    /**
     * 修改密码（需登录）：旧密码校验 → 新密码更新 → bump version 发新双 Token
     */
    public TokenPair changePassword(PasswordChangeDTO dto) {
        UserDTO userDTO = UserHolder.getUserDTO();
        if (userDTO == null) {
            throw new IllegalArgumentException("未登录");
        }
        Long userId = userDTO.getId();

        String oldPassword = dto.getOldPassword();
        String newPassword = dto.getNewPassword();

        if (oldPassword == null || newPassword == null) {
            throw new IllegalArgumentException("旧密码和新密码不能为空");
        }

        // 新密码强度校验
        if (RegexUtils.isPasswordInvalid(newPassword)) {
            throw new IllegalArgumentException("密码需至少8位，包含大写、小写、数字");
        }

        // 查用户
        User user = userAccountService.getById(userId);
        if (user == null || user.getPassword() == null) {
            throw new IllegalArgumentException("未设置密码，请使用验证码登录");
        }

        // 校验旧密码
        if (!PasswordEncoder.matches(oldPassword, user.getPassword())) {
            log.warn("修改密码失败：旧密码错误 userId={}", userId);
            throw new IllegalArgumentException("旧密码错误");
        }

        // 更新密码
        userAccountService.updatePassword(user, PasswordEncoder.encode(newPassword));

        // 生成全新双 Token（bump version → 旧 Token 自动失效）
        TokenPair tokenPair = tokenService.generateTokenPair(userId);
        log.info("【密码修改成功】userId={}, 已bump version", userId);
        return tokenPair;
    }

    /**
     * 重置密码（免登录）：验证码 + 新密码，免旧密码
     */
    public Result resetPassword(String phone, String code, String newPassword) {
        // 校验验证码
        if (!verifyCodeService.consumeVerifyCode(phone, code)) {
            return Result.fail(ErrorCode.BAD_REQUEST, "验证码错误或已过期");
        }
        // 校验密码强度
        if (RegexUtils.isPasswordInvalid(newPassword)) {
            return Result.fail(ErrorCode.BAD_REQUEST, "密码需至少8位，包含大写、小写、数字");
        }
        // 查用户
        User user = userAccountService.queryByPhone(phone);
        if (user == null) {
            return Result.fail(ErrorCode.NOT_FOUND, "该手机号未注册");
        }
        // 更新密码
        userAccountService.updatePassword(user, PasswordEncoder.encode(newPassword));
        log.info("密码重置成功 phone={}, userId={}", phone, user.getId());
        return Result.ok();
    }
}
