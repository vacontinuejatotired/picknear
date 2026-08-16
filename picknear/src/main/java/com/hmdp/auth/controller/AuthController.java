package com.hmdp.auth.controller;

import com.hmdp.auth.dto.LoginFormDTO;
import com.hmdp.auth.dto.PasswordChangeDTO;
import com.hmdp.auth.dto.TokenPair;
import com.hmdp.auth.login.LoginStrategyRegistry;
import com.hmdp.auth.password.PasswordService;
import com.hmdp.auth.session.SessionContextService;
import com.hmdp.auth.verifycode.VerifyCodeService;
import com.hmdp.dto.Result;
import com.hmdp.user.dto.UserDTO;
import com.hmdp.utils.RegexUtils;
import com.hmdp.utils.UserHolder;
import com.hmdp.utils.security.CookieWriter;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 认证控制器 — 登录/验证码/登出/改密/重置（P2-S6 自 UserController 迁出）
 * <p>
 * ⚠️ 对外 HTTP API 保持兼容：映射路径仍为 {@code /user/**}（与 UserController
 * 共享前缀、方法路径不重叠），URL/参数/响应契约不变。
 * </p>
 */
@Slf4j
@RestController
@RequestMapping("/user")
@Tag(name = "认证模块", description = "登录、验证码、登出、密码接口")
public class AuthController {

    @Resource
    private VerifyCodeService verifyCodeService;
    @Resource
    private LoginStrategyRegistry loginStrategyRegistry;
    @Resource
    private PasswordService passwordService;
    @Resource
    private SessionContextService sessionContextService;

    /**
     * 发送手机验证码
     */
    @PostMapping("code")
    @Operation(summary = "发送手机验证码", description = "向指定手机号发送登录验证码")
    public Result sendCode(
            @Parameter(description = "手机号") @RequestParam("phone") String phone) {
        return verifyCodeService.sendCode(phone);
    }

    /**
     * 登录功能 — Token 通过响应头返回（authorization + Refresh-Token）
     */
    @PostMapping("/login")
    @Operation(summary = "用户登录", description = "支持验证码登录或密码登录，返回Token对")
    public Result login(
            @Parameter(description = "登录表单") @RequestBody LoginFormDTO loginForm,
            HttpServletRequest request,
            HttpServletResponse response) {
        // 手机号格式校验（原 UserServiceImpl.login 公共校验，行为保持）
        if (RegexUtils.isPhoneInvalid(loginForm.getPhone())) {
            throw new IllegalArgumentException("手机号不规范");
        }
        // 策略路由：密码登录 / 验证码登录（supports 自判定）
        TokenPair tokenPair = loginStrategyRegistry.resolve(loginForm).login(loginForm);
        response.setHeader("authorization", "Bearer " + tokenPair.getAccessToken());
        setRefreshTokenCookie(request, response, tokenPair.getRefreshToken());
        return Result.ok();
    }

    /**
     * 登出功能 — 删除 Redis 中该用户的所有 Token/Version 记录，并清除客户端 Cookie
     */
    @PostMapping("/logout")
    @Operation(summary = "用户登出", description = "清除用户登出，清除Token记录")
    public Result logout(HttpServletResponse response) {
        UserDTO userDTO = UserHolder.getUserDTO();
        if (userDTO == null) {
            return Result.fail("未登录");
        }
        Long userId = userDTO.getId();
        sessionContextService.revokeTokens(userId);
        UserHolder.remove();
        // 清除客户端 access_token（前端 localStorage 不再更新）
        response.setHeader("authorization", "");
        // 清除客户端 refresh_token Cookie（MaxAge=0 使浏览器立即删除）
        Cookie clearCookie = new Cookie(CookieWriter.REFRESH_TOKEN_COOKIE_NAME, null);
        clearCookie.setHttpOnly(true);
        clearCookie.setPath("/");
        clearCookie.setMaxAge(0);
        response.addCookie(clearCookie);
        log.info("用户登出成功 userId={}", userId);
        return Result.ok();
    }

    /**
     * 修改密码
     *
     * @return 新 TokenPair（旧 Token 自动失效）
     */
    @PutMapping("/password")
    @Operation(summary = "修改密码", description = "修改当前用户密码")
    public Result changePassword(
            @Parameter(description = "密码修改表单") @RequestBody PasswordChangeDTO dto,
            HttpServletRequest request,
            HttpServletResponse response) {
        TokenPair tokenPair = passwordService.changePassword(dto);
        response.setHeader("authorization", "Bearer " + tokenPair.getAccessToken());
        setRefreshTokenCookie(request, response, tokenPair.getRefreshToken());
        log.info("密码修改成功 userId={}", UserHolder.getUserId());
        return Result.ok();
    }

    /**
     * 重置密码 — 验证码 + 新密码，免旧密码
     */
    @PutMapping("/password/reset")
    @Operation(summary = "重置密码", description = "通过验证码重置密码")
    public Result resetPassword(
            @Parameter(description = "手机号") @RequestParam("phone") String phone,
            @Parameter(description = "验证码") @RequestParam("code") String code,
            @Parameter(description = "新密码") @RequestParam("newPassword") String newPassword) {
        return passwordService.resetPassword(phone, code, newPassword);
    }

    /**
     * 设置 Refresh Token 到 httpOnly Cookie（JS 不可读，自动随请求发送）
     * SameSite/Secure 由 CookieWriter 按连接是否 HTTPS 动态判断
     */
    private void setRefreshTokenCookie(HttpServletRequest request, HttpServletResponse response, String refreshToken) {
        // 双通道：httpOnly Cookie（同源自动携带）+ 响应头（前端存 localStorage 显式携带），
        // 后端拦截器 Cookie/头 读到任一个即可刷新，规避跨 host / 浏览器清 cookie 导致的丢失
        response.addHeader("Set-Cookie", CookieWriter.refreshTokenCookie(refreshToken, request.isSecure()));
        response.setHeader("Refresh-Token", refreshToken);
    }
}
