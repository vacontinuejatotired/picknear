package com.hmdp.utils;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.RandomUtil;
import com.baomidou.mybatisplus.extension.conditions.query.QueryChainWrapper;
import com.hmdp.entity.User;
import com.hmdp.mapper.UserMapper;
import com.hmdp.utils.constants.SystemConstants;
import com.hmdp.utils.redis.RedisConstants;
import com.hmdp.utils.redis.RedisIdWorker;
import com.hmdp.utils.security.JwtUtil;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Token 测试工具 — 批量生成用户 Token 并导出，独立于生产 Service
 */
@Slf4j
@Component
public class TokenTestUtil {

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private RedisIdWorker redisIdWorker;

    @Resource
    private JwtUtil jwtUtil;

    @Resource
    private UserMapper userMapper;

    private final List<String> tokenList = new ArrayList<>();
    private final List<String> phoneList = new ArrayList<>();
    private final List<String> refreshTokenList = new ArrayList<>();

    public List<String> getTokenList() {
        return new ArrayList<>(tokenList);
    }

    public List<String> getPhoneList() {
        return new ArrayList<>(phoneList);
    }

    private String generateTestPhone(int index) {
        return String.format("137%08d", index);
    }

    /**
     * 批量生成 Token 并写入 Redis
     */
    public void generateTestTokenAndRefreshToken(int size) {
        tokenList.clear();
        refreshTokenList.clear();
        if (size <= 0) {
            log.error("size must be positive, current size: {}", size);
            return;
        }
        if (phoneList.isEmpty()) {
            log.error("PHONE_LIST is empty");
            return;
        }

        List<User> existingUsers = userMapper.selectList(null).stream()
                .filter(u -> phoneList.contains(u.getPhone()))
                .collect(Collectors.toList());

        Set<String> existingPhones = existingUsers.stream()
                .map(User::getPhone)
                .collect(Collectors.toSet());
        List<User> newUsers = new ArrayList<>();
        for (String phone : phoneList) {
            if (!existingPhones.contains(phone)) {
                User newUser = new User()
                        .setPhone(phone)
                        .setCreateTime(LocalDateTime.now())
                        .setUpdateTime(LocalDateTime.now());
                newUsers.add(newUser);
            }
        }
        if (!newUsers.isEmpty()) {
            newUsers.forEach(u -> userMapper.insert(u));
            existingUsers = userMapper.selectList(null).stream()
                    .filter(u -> phoneList.contains(u.getPhone()))
                    .collect(Collectors.toList());
        }

        Map<Long, Long> userVersionMap = new HashMap<>();
        for (User user : existingUsers) {
            Long version = redisIdWorker.nextVersion(user.getId());
            String token = jwtUtil.generateToken(user.getId(), 1800L, ChronoUnit.SECONDS, version);
            if (token == null || token.isEmpty()) continue;
            tokenList.add(token);
            String refreshToken = UUID.randomUUID().toString().replace("-", "");
            refreshTokenList.add(refreshToken);
            userVersionMap.put(user.getId(), version);
        }

        if (!tokenList.isEmpty() && tokenList.size() == refreshTokenList.size()) {
            List<User> finalUsers = existingUsers;
            stringRedisTemplate.executePipelined((RedisCallback<Object>) connection -> {
                for (int i = 0; i < tokenList.size(); i++) {
                    String t = tokenList.get(i);
                    String rt = refreshTokenList.get(i);
                    User u = finalUsers.get(i);
                    Long ver = userVersionMap.get(u.getId());

                    String tk = RedisConstants.LOGIN_USER_KEY + u.getId();
                    connection.set(tk.getBytes(StandardCharsets.UTF_8), t.getBytes(StandardCharsets.UTF_8));
                    connection.expire(tk.getBytes(StandardCharsets.UTF_8), 1800L);

                    String rk = RedisConstants.LOGIN_REFRESH_USER_KEY + u.getId();
                    connection.set(rk.getBytes(StandardCharsets.UTF_8), rt.getBytes(StandardCharsets.UTF_8));
                    connection.expire(rk.getBytes(StandardCharsets.UTF_8), RedisConstants.LOGIN_REFRESHTOKEN_TTL_SECONDS);

                    String vk = RedisConstants.LOGIN_VALID_VERSION_KEY + u.getId();
                    connection.set(vk.getBytes(StandardCharsets.UTF_8), ver.toString().getBytes(StandardCharsets.UTF_8));
                    connection.expire(vk.getBytes(StandardCharsets.UTF_8), RedisConstants.LOGIN_REFRESHTOKEN_TTL_SECONDS);

                    String nvk = RedisConstants.CURRENT_TOKEN_VERSION_KEY + u.getId();
                    connection.set(nvk.getBytes(StandardCharsets.UTF_8), ver.toString().getBytes(StandardCharsets.UTF_8));
                    connection.expire(nvk.getBytes(StandardCharsets.UTF_8), 8 * 24 * 60 * 60);
                }
                return null;
            });
            log.info("Inserted {} tokens into Redis", tokenList.size());
        }
    }

    /**
     * 批量生成 Token 并导出到 CSV
     */
    public void exportTokenAndRefreshTokenToCsv(int size, String fileName) {
        phoneList.clear();
        tokenList.clear();
        refreshTokenList.clear();
        for (int i = 0; i < size; i++) {
            phoneList.add(generateTestPhone(i));
        }
        generateTestTokenAndRefreshToken(size);
        String filePath = "tokens_" + fileName + ".csv";
        try (FileWriter writer = new FileWriter(filePath);
             PrintWriter pw = new PrintWriter(writer)) {
            pw.println("token,refreshToken");
            for (int i = 0; i < phoneList.size(); i++) {
                pw.printf("%s,%s%n", tokenList.get(i), refreshTokenList.get(i));
            }
            log.info("CSV exported: {}", filePath);
        } catch (IOException e) {
            log.error("Failed to write CSV", e);
        }
    }
}
