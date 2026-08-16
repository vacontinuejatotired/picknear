package com.hmdp.auth.verifycode;

import cn.hutool.core.util.RandomUtil;
import com.hmdp.dto.Result;
import com.hmdp.enums.ErrorCode;
import com.hmdp.utils.RegexUtils;
import com.hmdp.utils.redis.RedisConstants;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import jakarta.annotation.Resource;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * 验证码服务 — 发送与原子消费（auth 域收敛，P2-S1）
 * <p>
 * 职责：频率限制、生成、存储（{@link RedisConstants#LOGIN_CODE_KEY}）、
 * 原子消费（ConsumeVerifyCode.lua GET+DEL 防重放）。
 * 无 HTTP 依赖；DEV 验证码回显（审查报告 T-4 上线前条目）保留。
 * </p>
 */
@Slf4j
@Component
public class VerifyCodeService {

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource(name = "consumeVerifyCodeScript")
    private DefaultRedisScript<String> consumeVerifyCodeScript;

    /** 发送验证码（含 60s 频率限制；DEV 环境回显验证码便于调试） */
    public Result sendCode(String phone) {
        if (RegexUtils.isPhoneInvalid(phone)) {
            throw new IllegalArgumentException("手机号不规范");
        }
        String freqKey = RedisConstants.LOGIN_CODE_FREQ_KEY + phone;
        if (Boolean.TRUE.equals(stringRedisTemplate.hasKey(freqKey))) {
            return Result.fail(ErrorCode.TOO_MANY_REQUESTS, "发送太频繁，请稍后再试");
        }
        String code = RandomUtil.randomNumbers(6);
        stringRedisTemplate.opsForValue().set(RedisConstants.LOGIN_CODE_KEY + phone,
                code, RedisConstants.LOGIN_CODE_TTL, TimeUnit.MINUTES);
        stringRedisTemplate.opsForValue().set(freqKey, "1", 60, TimeUnit.SECONDS);
        log.info("send code {} success", code);
        // DEV ONLY: 返回验证码便于开发调试，生产环境应移除
        return Result.ok(code);
    }

    /** 原子消费验证码：GET + DEL，防止重放 */
    public boolean consumeVerifyCode(String phone, String code) {
        if (code == null) {
            return false;
        }
        String tempCode = stringRedisTemplate.execute(consumeVerifyCodeScript,
                List.of(RedisConstants.LOGIN_CODE_KEY + phone));
        return code.equals(tempCode);
    }
}
