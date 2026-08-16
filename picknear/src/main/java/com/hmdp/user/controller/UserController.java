package com.hmdp.user.controller;

import com.hmdp.dto.Result;
import com.hmdp.enums.ErrorCode;
import com.hmdp.user.dto.ProfileUpdateDTO;
import com.hmdp.user.entity.UserInfo;
import com.hmdp.user.profile.ProfileService;
import com.hmdp.user.query.UserQueryService;
import com.hmdp.user.service.ISignService;
import com.hmdp.user.service.IUserInfoService;
import com.hmdp.utils.UserHolder;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import jakarta.annotation.Resource;
import java.io.IOException;

/**
 * 用户控制器 — 用户信息查询、资料编辑、签到（P2-S6 瘦身后）
 * <p>
 * 认证端点（登录/验证码/登出/密码）已迁至 {@code com.hmdp.auth.controller.AuthController}，
 * 路径仍为 {@code /user/**}，对外 API 不变；查询/资料逻辑下沉
 * {@link UserQueryService} / {@link ProfileService}（消除分层击穿）。
 * </p>
 */
@Slf4j
@RestController
@RequestMapping("/user")
@Tag(name = "用户模块", description = "用户信息查询、资料编辑、签到等接口")
public class UserController {

    @Resource
    private IUserInfoService userInfoService;
    @Resource
    private ISignService signService;
    @Resource
    private UserQueryService userQueryService;
    @Resource
    private ProfileService profileService;

    @GetMapping("/me")
    @Operation(summary = "获取当前用户信息", description = "获取当前登录用户的基本信息")
    public Result me() {
        log.debug("me() userId={}", UserHolder.getUserId());
        return Result.ok(UserHolder.getUserDTO());
    }

    @GetMapping("/{id}")
    @Operation(summary = "查询用户信息", description = "根据用户ID查询用户信息")
    public Result queryUserById(
            @Parameter(description = "用户ID") @PathVariable("id") Long userId) {
        return userQueryService.queryUserById(userId);
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
    public Result sign() {
        return signService.sign();
    }

    @GetMapping("/sign/count")
    @Operation(summary = "签到统计", description = "查询连续签到天数统计")
    public Result signCount() {
        return signService.getSignCount();
    }

    /**
     * 编辑个人资料 — multipart/form-data 方式
     *
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
            iconUrl = profileService.uploadIcon(iconFile);
        }

        ProfileUpdateDTO dto = new ProfileUpdateDTO();
        dto.setNickName(blankToNull(nickName));
        dto.setCity(blankToNull(city));
        dto.setIntroduce(blankToNull(introduce));
        dto.setIcon(iconUrl);
        return profileService.updateProfile(dto);
    }

    /** 空字符串转 null，避免 Service 层误将空串当作有效值 */
    private static String blankToNull(String s) {
        return (s == null || s.isBlank()) ? null : s.strip();
    }
}
