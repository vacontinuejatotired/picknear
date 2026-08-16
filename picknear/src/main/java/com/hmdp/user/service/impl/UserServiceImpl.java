package com.hmdp.user.service.impl;

import cn.hutool.core.util.RandomUtil;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.auth.dto.LoginFormDTO;
import com.hmdp.auth.dto.PasswordChangeDTO;
import com.hmdp.auth.dto.TokenPair;
import com.hmdp.auth.service.AuthService;
import com.hmdp.auth.password.PasswordService;
import com.hmdp.auth.verifycode.VerifyCodeService;
import com.hmdp.dto.Result;
import com.hmdp.service.FileService;
import com.hmdp.user.dto.ProfileUpdateDTO;
import com.hmdp.user.dto.UserDTO;
import com.hmdp.user.entity.User;
import com.hmdp.user.entity.UserInfo;
import com.hmdp.user.entity.UserinfoCache;
import com.hmdp.user.mapper.UserMapper;
import com.hmdp.user.service.IUserInfoService;
import com.hmdp.user.service.IUserService;
import com.hmdp.utils.RegexUtils;
import com.hmdp.utils.UserHolder;
import com.hmdp.utils.constants.SystemConstants;
import com.hmdp.utils.redis.RedisConstants;
import com.hmdp.utils.security.PasswordEncoder;
import lombok.extern.slf4j.Slf4j;
import com.github.benmanes.caffeine.cache.LoadingCache;
import com.hmdp.utils.cache.CaffeineConstants;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import jakarta.annotation.Resource;
import org.springframework.context.annotation.Lazy;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.TimeUnit;

/**
 * 用户服务实现 — 手机验证码登录、密码登录、签到（Redis BitMap）、双Token生成
 */
@Slf4j
@Service
public class UserServiceImpl extends ServiceImpl<UserMapper, User> implements IUserService {
    @Resource
    private StringRedisTemplate stringRedisTemplate;
    @Resource
    @Lazy
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
//    @Transactional(rollbackFor = SQLException.class)
//    @AutoUpdateTime(printLog = true)
    public TokenPair login(LoginFormDTO loginForm) {
        String password = loginForm.getPassword();
        String phone = loginForm.getPhone();

        // ① 手机号格式校验
        if (RegexUtils.isPhoneInvalid(phone)) {
            throw new IllegalArgumentException("手机号不规范");
        }

        // ② 密码登录 / 验证码登录 二选一
        if (password != null) {
            return loginByPassword(phone, password);
        }
        return loginByCode(phone, loginForm.getCode());
    }

    /**
     * 密码登录（含自动注册：新手机号+密码首次登录即创建账号）
     */
    private TokenPair loginByPassword(String phone, String password) {
        // 查用户
        User user = query().eq("phone", phone).one();

        // 新用户自动注册：手机号不存在则创建账号，直接生成 Token 返回
        if (user == null) {
            user = createUser(phone, PasswordEncoder.encode(password));
            log.info("【密码登录成功（新用户）】userId={}", user.getId());
            return authService.generateTokenPair(user.getId());
        }

        // 已有用户：校验密码
        if (user.getPassword() == null) {
            throw new IllegalArgumentException("该手机号未设置密码，请使用验证码登录");
        }

        // 账户锁定检查
        String lockKey = RedisConstants.LOGIN_LOCK_KEY + phone;
        if (Boolean.TRUE.equals(stringRedisTemplate.hasKey(lockKey))) {
            throw new IllegalArgumentException("账户已锁定，请 30 分钟后重试");
        }

        // 校验密码
        if (!PasswordEncoder.matches(password, user.getPassword())) {
            // 失败计数累加
            String failKey = RedisConstants.LOGIN_FAIL_COUNT_KEY + phone;
            Long fails = stringRedisTemplate.opsForValue().increment(failKey);
            if (fails == 1) {
                stringRedisTemplate.expire(failKey, RedisConstants.LOGIN_FAIL_COUNT_TTL, TimeUnit.SECONDS);
            }
            if (fails >= RedisConstants.LOGIN_FAIL_COUNT_LOCK) {
                stringRedisTemplate.opsForValue().set(lockKey, "1",
                        RedisConstants.LOGIN_LOCK_TTL, TimeUnit.SECONDS);
                log.warn("账户连续登录失败被锁定 phone={}", phone);
                stringRedisTemplate.delete(failKey);
                throw new IllegalArgumentException("账户已锁定，请 30 分钟后重试");
            }
            log.info("密码错误 phone={}, 失败次数={}", phone, fails);
            throw new IllegalArgumentException("账号或密码错误");
        }

        // 登录成功：清除失败计数
        stringRedisTemplate.delete(RedisConstants.LOGIN_FAIL_COUNT_KEY + phone);

        // 旧 MD5 格式密码 → 自动升级为 bcrypt
        if (!user.getPassword().startsWith("$2")) {
            user.setPassword(PasswordEncoder.encode(password));
            user.setUpdateTime(LocalDateTime.now());
            updateById(user);
            log.info("密码自动升级为 bcrypt userId={}", user.getId());
        }

        // 生成 Token
        log.info("【密码登录成功】userId={}", user.getId());
        return authService.generateTokenPair(user.getId());
    }

    /**
     * 验证码登录（原有逻辑）
     */
    private TokenPair loginByCode(String phone, String code) {
        // 原子消费验证码
        if (!verifyCodeService.consumeVerifyCode(phone, code)) {
            log.info("验证码错误 phone={}, code={}", phone, code);
            throw new IllegalArgumentException("验证码错误");
        }

        // 查用户 → 不存在则自动创建
        User user = query().eq("phone", phone).one();
        if (user == null) {
            user = createUser(phone, null);
        }

        // 生成 Token
        log.info("【验证码登录成功】userId={}", user.getId());
        return authService.generateTokenPair(user.getId());
    }

    /**
     * 自动注册：创建 User 记录 + 同步创建 UserInfo 记录（nickName 已迁移至此）。
     * 密码登录/验证码登录共用，消除重复注册逻辑。
     *
     * @param encodedPassword 加密后的密码；验证码注册传 null（无密码）
     */
    private User createUser(String phone, String encodedPassword) {
        String nickName = SystemConstants.USER_NICK_NAME_PREFIX + RandomUtil.randomNumbers(6);
        User user = new User().setPhone(phone)
                .setCreateTime(LocalDateTime.now())
                .setUpdateTime(LocalDateTime.now());
        if (encodedPassword != null) {
            user.setPassword(encodedPassword);
        }
        save(user);
        // 同步创建 UserInfo 记录
        UserInfo newInfo = new UserInfo();
        newInfo.setUserId(user.getId());
        newInfo.setNickName(nickName);
        userInfoService.save(newInfo);
        log.info("新用户已创建 phone={}, userId={}", phone, user.getId());
        return user;
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

