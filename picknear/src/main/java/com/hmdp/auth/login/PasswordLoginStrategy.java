package com.hmdp.auth.login;

import com.hmdp.auth.dto.LoginFormDTO;
import com.hmdp.auth.dto.TokenPair;
import com.hmdp.auth.token.TokenService;
import com.hmdp.user.account.UserAccountService;
import com.hmdp.user.entity.User;
import com.hmdp.utils.redis.RedisConstants;
import com.hmdp.utils.security.PasswordEncoder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import jakarta.annotation.Resource;
import java.time.LocalDateTime;
import java.util.concurrent.TimeUnit;

/**
 * 密码登录策略 — 密码 + BCrypt + 账户锁定/失败计数 + 密码自动升级
 * <p>
 * 自 UserServiceImpl.loginByPassword 迁出（P2-S5），行为等价：
 * <ul>
 *   <li>新手机号 + 密码首次登录即自动注册（createUser）</li>
 *   <li>连续失败 {@code LOGIN_FAIL_COUNT_LOCK} 次锁定 30 分钟</li>
 *   <li>旧 MD5 格式密码登录成功时自动升级为 bcrypt</li>
 * </ul>
 * </p>
 */
@Slf4j
@Component
public class PasswordLoginStrategy implements LoginStrategy {

    @Resource
    private UserAccountService userAccountService;
    @Resource
    private TokenService tokenService;
    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Override
    public boolean supports(LoginFormDTO form) {
        return form.getPassword() != null;
    }

    @Override
    public TokenPair login(LoginFormDTO form) {
        String phone = form.getPhone();
        String password = form.getPassword();

        // 查用户
        User user = userAccountService.queryByPhone(phone);

        // 新用户自动注册：手机号不存在则创建账号，直接生成 Token 返回
        if (user == null) {
            user = userAccountService.createUser(phone, PasswordEncoder.encode(password));
            log.info("【密码登录成功（新用户）】userId={}", user.getId());
            return tokenService.generateTokenPair(user.getId());
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
            userAccountService.updatePassword(user, PasswordEncoder.encode(password));
            log.info("密码自动升级为 bcrypt userId={}", user.getId());
        }

        // 生成 Token
        log.info("【密码登录成功】userId={}", user.getId());
        return tokenService.generateTokenPair(user.getId());
    }
}
