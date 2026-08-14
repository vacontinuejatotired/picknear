package com.hmdp.controller;


import cn.hutool.core.bean.BeanUtil;
import com.hmdp.dto.LoginFormDTO;
import com.hmdp.dto.PasswordChangeDTO;
import com.hmdp.dto.ProfileUpdateDTO;
import com.hmdp.dto.Result;
import com.hmdp.dto.TokenPair;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.User;
import com.hmdp.entity.UserInfo;
import com.hmdp.enums.ErrorCode;
import com.hmdp.service.FileService;
import com.hmdp.service.IUserInfoService;
import com.hmdp.service.IUserService;
import com.hmdp.utils.UserHolder;
import com.hmdp.utils.cache.CacheClient;
import com.hmdp.utils.redis.RedisConstants;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import jakarta.annotation.Resource;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * <p>
 * 用户控制器 — 注册、登录、用户信息查询、签到
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Slf4j
@RestController
@RequestMapping("/user")
@Tag(name = "用户模块", description = "用户登录、注册、信息查询、签到等接口")
public class UserController {

    @Resource
    private IUserService userService;

    @Resource
    private IUserInfoService userInfoService;
    @Resource
    private CacheClient cacheClient;
    @Resource
    private FileService fileService;

    private static final Set<String> ALLOWED_ICON_TYPES = Set.of("jpg", "jpeg", "png", "gif", "webp");
    private static final long MAX_ICON_SIZE = 2 * 1024 * 1024L;

    /**
     * 发送手机验证码
     */
    @PostMapping("code")
    @Operation(summary = "发送手机验证码", description = "向指定手机号发送登录验证码")
    public Result sendCode(
            @Parameter(description = "手机号") @RequestParam("phone") String phone) {
        return userService.sendCode(phone);
    }

    /**
     * 登录功能 — Token 通过响应头返回（authorization + Refresh-Token）
     * @param loginForm 登录参数，包含手机号、验证码；或者手机号、密码
     * @param response 用于写回 Token 响应头
     */
    @PostMapping("/login")
    @Operation(summary = "用户登录", description = "支持验证码登录或密码登录，返回Token对")
    public Result login(
            @Parameter(description = "登录表单") @RequestBody LoginFormDTO loginForm,
            HttpServletRequest request,
            HttpServletResponse response) {
        TokenPair tokenPair = userService.login(loginForm);
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
        userService.logout(userId);
        UserHolder.remove();
        // 清除客户端 access_token（前端 localStorage 不再更新）
        response.setHeader("authorization", "");
        // 清除客户端 refresh_token Cookie（MaxAge=0 使浏览器立即删除）
        Cookie clearCookie = new Cookie("refresh_token", null);
        clearCookie.setHttpOnly(true);
        clearCookie.setPath("/");
        clearCookie.setMaxAge(0);
        response.addCookie(clearCookie);
        log.info("用户登出成功 userId={}", userId);
        return Result.ok();
    }

    @GetMapping("/me")
    @Operation(summary = "获取当前用户信息", description = "获取当前登录用户的基本信息")
    public Result me(){
        log.debug("me() userId={}", UserHolder.getUserId());
        return Result.ok(UserHolder.getUserDTO());
    }

    @GetMapping("/{id}")
    @Operation(summary = "查询用户信息", description = "根据用户ID查询用户信息")
    public Result queryUserById(
            @Parameter(description = "用户ID") @PathVariable("id") Long userId) {
        // 缓存查询（缓存穿透防护）
        User user = cacheClient.queryById(userId, User.class, "cache:user:",
                id -> userService.getById(id), 30L, TimeUnit.MINUTES);
        if (user == null) {
            return Result.ok();
        }
        UserDTO userDTO = BeanUtil.copyProperties(user, UserDTO.class);
        // nickName、icon 已迁移到 tb_user_info，需要额外查询
        UserInfo userInfo = userInfoService.getById(userId);
        if (userInfo != null) {
            userDTO.setNickName(userInfo.getNickName());
            userDTO.setIcon(userInfo.getIcon());
        }
        return Result.ok(userDTO);
    }
    @GetMapping("/info/{id}")
    @Operation(summary = "查询用户详情", description = "查询用户详细信息（仅自己可查）")
    public Result info(
            @Parameter(description = "用户ID") @PathVariable("id") Long userId) {
        // 权限校验：只能查自己的详细信息
        Long currentUserId = UserHolder.getUserId();
        if (!currentUserId.equals(userId)) {
            log.warn("越权访问详情: currentUserId={}, targetUserId={}", currentUserId, userId);
            return Result.fail(ErrorCode.FORBIDDEN, "无权访问该用户详细信息");
        }
        // 查询详情
        UserInfo info = userInfoService.getById(userId);
        if (info == null) {
            // 没有详情，应该是第一次查看详情
            return Result.ok();
        }
        info.setCreateTime(null);
        info.setUpdateTime(null);
        // 返回
        return Result.ok(info);
    }
    @PostMapping("/sign")
    @Operation(summary = "用户签到", description = "今日签到功能")
    public Result sign(){
        return userService.sign();
    }
    @GetMapping("/sign/count")
    @Operation(summary = "签到统计", description = "查询连续签到天数统计")
    public Result signCount(){
        return userService.getSignCount();
    }

    /**
     * 修改密码
     * @param dto 旧密码 + 新密码
     * @return 新 TokenPair（旧 Token 自动失效）
     */
    @PutMapping("/password")
    @Operation(summary = "修改密码", description = "修改当前用户密码")
    public Result changePassword(
            @Parameter(description = "密码修改表单") @RequestBody PasswordChangeDTO dto,
            HttpServletRequest request,
            HttpServletResponse response){
        TokenPair tokenPair = userService.changePassword(dto);
        response.setHeader("authorization", "Bearer " + tokenPair.getAccessToken());
        setRefreshTokenCookie(request, response, tokenPair.getRefreshToken());
        log.info("密码修改成功 userId={}", UserHolder.getUserId());
        return Result.ok();
    }

    /**
     * 编辑个人资料 — multipart/form-data 方式
     * @param iconFile  头像文件（可选），上传到 OSS/icons/ 目录
     * @param nickName  昵称（可选）
     * @param city      城市（可选）
     * @param introduce 个人简介（可选）
     */
    @PutMapping("/profile")
    @Operation(summary = "编辑个人资料", description = "修改用户昵称、头像、城市、简介等信息")
    public Result updateProfile(
            @Parameter(description = "头像文件") @RequestParam(required = false) MultipartFile iconFile,
            @Parameter(description = "昵称") @RequestParam(required = false) String nickName,
            @Parameter(description = "城市") @RequestParam(required = false) String city,
            @Parameter(description = "个人简介") @RequestParam(required = false) String introduce) throws IOException {

        String iconUrl = null;
        if (iconFile != null && !iconFile.isEmpty()) {
            iconUrl = uploadIcon(iconFile);
        }

        ProfileUpdateDTO dto = new ProfileUpdateDTO();
        dto.setNickName(blankToNull(nickName));
        dto.setCity(blankToNull(city));
        dto.setIntroduce(blankToNull(introduce));
        dto.setIcon(iconUrl);
        return userService.updateProfile(dto);
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
        return userService.resetPassword(phone, code, newPassword);
    }

    /** 空字符串转 null，避免 Service 层误将空串当作有效值 */
    private static String blankToNull(String s) {
        return (s == null || s.isBlank()) ? null : s.strip();
    }

    /**
     * 上传头像到 FileService（icons/ 目录），含基本校验
     */
    private String uploadIcon(MultipartFile iconFile) throws IOException {
        String originalFilename = iconFile.getOriginalFilename();
        if (originalFilename == null || originalFilename.isBlank()) {
            throw new IllegalArgumentException("文件名不能为空");
        }
        String ext = cn.hutool.core.util.StrUtil.subAfter(originalFilename, ".", true).toLowerCase();
        if (!ALLOWED_ICON_TYPES.contains(ext)) {
            throw new IllegalArgumentException("不支持的头像格式，仅允许: " + ALLOWED_ICON_TYPES);
        }
        if (iconFile.getSize() > MAX_ICON_SIZE) {
            throw new IllegalArgumentException("头像文件过大，最大允许 2MB");
        }
        return fileService.upload(iconFile.getInputStream(), originalFilename, "icons");
    }

    /**
     * 设置 Refresh Token 到 httpOnly Cookie（JS 不可读，自动随请求发送）
     * SameSite/Secure 按当前连接是否 HTTPS 动态判断：
     * HTTPS 连接 → SameSite=None + Secure；HTTP 连接 → SameSite=Lax 不加 Secure
     * （避免浏览器因 Secure 标志拒绝 HTTP cookie；跨源 HTTPS 场景需 None+Secure）
     */
    private void setRefreshTokenCookie(HttpServletRequest request, HttpServletResponse response, String refreshToken) {
        boolean isSecure = request.isSecure();
        String sameSite = isSecure ? "None" : "Lax";
        response.addHeader("Set-Cookie", String.format(
                "%s=%s; HttpOnly; %sSameSite=%s; Path=/; MaxAge=%d",
                "refresh_token", refreshToken,
                isSecure ? "Secure; " : "",
                sameSite,
                7 * 24 * 60 * 60
        ));
        // 双通道：httpOnly Cookie（同源自动携带）+ 响应头（前端存 localStorage 显式携带），
        // 后端拦截器 Cookie/头 读到任一个即可刷新，规避跨 host / 浏览器清 cookie 导致的丢失
        response.setHeader("Refresh-Token", refreshToken);
    }
}
