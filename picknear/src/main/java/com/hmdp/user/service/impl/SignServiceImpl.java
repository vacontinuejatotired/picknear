package com.hmdp.user.service.impl;

import com.hmdp.dto.Result;
import com.hmdp.user.service.ISignService;
import com.hmdp.utils.UserHolder;
import com.hmdp.utils.redis.RedisConstants;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.connection.BitFieldSubCommands;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import jakarta.annotation.Resource;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * 签到服务实现 — 按「年-月 + userId」建 BitMap，位下标 = 日-1；统计时从今天往前连续为 1 的天数。
 */
@Slf4j
@Service
public class SignServiceImpl implements ISignService {

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Override
    public Result sign() {
        Long userId = UserHolder.getUserId();
        LocalDateTime now = LocalDateTime.now();
        String key = signKey(userId, now);
        int dayOfMonth = now.getDayOfMonth();
        stringRedisTemplate.opsForValue().setBit(key, dayOfMonth - 1, true);
        return Result.ok();
    }

    @Override
    public Result getSignCount() {
        Long userId = UserHolder.getUserId();
        LocalDateTime now = LocalDateTime.now();
        String key = signKey(userId, now);
        int dayOfMonth = now.getDayOfMonth();
        List<Long> bitField = stringRedisTemplate.opsForValue().bitField(key,
                BitFieldSubCommands.create()
                        .get(BitFieldSubCommands.BitFieldType.unsigned(dayOfMonth))
                        .valueAt(0));
        if (bitField == null || bitField.isEmpty()) {
            return Result.ok(0);
        }
        Long num = bitField.get(0);
        if (num == null || num == 0) {
            return Result.ok(0);
        }
        int count = 0;
        while (true) {
            if ((num & 1) == 0) {
                // 代表未签到
                break;
            } else {
                count++;
            }
            // 无符号右移
            num >>>= 1;
        }
        return Result.ok(count);
    }

    /** 签到 BitMap key：{年-月}{userId}{USER_SIGN_KEY 后缀}（与旧实现 key 格式保持一致） */
    private String signKey(Long userId, LocalDateTime now) {
        String yearMonth = now.format(DateTimeFormatter.ofPattern("yyyy-MM"));
        return yearMonth + userId + RedisConstants.USER_SIGN_KEY;
    }
}
