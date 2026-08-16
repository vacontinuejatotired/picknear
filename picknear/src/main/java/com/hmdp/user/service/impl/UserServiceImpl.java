package com.hmdp.user.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.github.benmanes.caffeine.cache.LoadingCache;
import com.hmdp.auth.dto.LoginFormDTO;
import com.hmdp.auth.dto.PasswordChangeDTO;
import com.hmdp.auth.dto.TokenPair;
import com.hmdp.auth.login.LoginStrategyRegistry;
import com.hmdp.auth.password.PasswordService;
import com.hmdp.auth.service.AuthService;
import com.hmdp.auth.verifycode.VerifyCodeService;
import com.hmdp.dto.Result;
import com.hmdp.service.FileService;
import com.hmdp.user.dto.ProfileUpdateDTO;
import com.hmdp.user.entity.User;
import com.hmdp.user.entity.UserInfo;
import com.hmdp.user.entity.UserinfoCache;
import com.hmdp.user.mapper.UserMapper;
import com.hmdp.user.service.IUserInfoService;
import com.hmdp.user.service.IUserService;
import com.hmdp.utils.RegexUtils;
import com.hmdp.utils.UserHolder;
import com.hmdp.utils.cache.CaffeineConstants;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import jakarta.annotation.Resource;

/**
 * 用户服务实现 — P2 拆分后收敛为：登录入口（策略路由）+ 资料域 + 认证委托
 * <p>
 * 职责边界（2026-08 P2 拆分后）：
 * <ul>
 *   <li>登录：LoginStrategyRegistry 策略路由（密码/验证码，auth.login）</li>
 *   <li>资料：updateProfile（P2-S6 迁 ProfileService 后移除）</li>
 *   <li>委托：验证码 / 改密 / 重置 / 登出（VerifyCodeService / PasswordService / AuthService）</li>
 * </ul>
 * </p>
 */
@Slf4j
@Service
public class UserServiceImpl extends ServiceImpl<UserMapper, User> implements IUserService {

    @Resource
    private LoginStrategyRegistry loginStrategyRegistry;
    @Resource
    private AuthService authService;
    @Resource
    private VerifyCodeService verifyCodeService;
    @Resource
    private PasswordService passwordService;
    @Resource
    private IUserInfoService userInfoService;
    @Resource
    private FileService fileService;
    @Resource(name = "userinfoCache")
    private LoadingCache<String, UserinfoCache> userinfoCaffeine;

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
        authService.revokeTokens(userId);
    }

    @Override
    public Result updateProfile(ProfileUpdateDTO dto) {
        Long userId = UserHolder.getUserId();

        // 从 DB 查出现有记录，避免 new UserInfo() 时默认值 "" 覆盖原有字段
        UserInfo userInfo = userInfoService.getById(userId);
        if (userInfo == null) {
            userInfo = new UserInfo();
            userInfo.setUserId(userId);
        }
        boolean needUpdateInfo = false;
        if (dto.getNickName() != null) {
            String nickName = dto.getNickName().strip();
            if (nickName.isEmpty()) {
                return Result.fail("昵称不能为空");
            }
            userInfo.setNickName(nickName);
            needUpdateInfo = true;
        }
        if (dto.getIcon() != null) {
            // 新旧 icon 不同时删除旧文件
            String oldIcon = userInfo.getIcon();
            if (oldIcon != null && !oldIcon.isEmpty()
                    && !oldIcon.equals(dto.getIcon())) {
                fileService.delete(oldIcon);
                log.info("已删除旧头像: userId={}, oldIcon={}", userId, oldIcon);
            }
            userInfo.setIcon(dto.getIcon());
            needUpdateInfo = true;
        }
        if (dto.getCity() != null) {
            userInfo.setCity(dto.getCity());
            needUpdateInfo = true;
        }
        if (dto.getIntroduce() != null) {
            userInfo.setIntroduce(dto.getIntroduce());
            needUpdateInfo = true;
        }
        if (needUpdateInfo) {
            userInfoService.updateById(userInfo);
            // 从 DB 查完整数据后刷新 Caffeine 缓存
            String userInfoKey = CaffeineConstants.USERINFO_CACHE_KEY + userId;
            UserInfo fresh = userInfoService.getById(userId);
            if (fresh != null) {
                UserinfoCache newCache = new UserinfoCache(userId, fresh.getNickName(), fresh.getIcon());
                userinfoCaffeine.put(userInfoKey, newCache);
                log.debug("已更新用户缓存 userId={}", userId);
            }
        }

        log.info("用户 {} 更新个人资料: nickName={}, icon={}, city={}, introduce={}",
                userId, dto.getNickName(), dto.getIcon(), dto.getCity(), dto.getIntroduce());
        return Result.ok();
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
