package com.hmdp.utils.security;

import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.util.DigestUtils;

import java.nio.charset.StandardCharsets;

/**
 * 密码编码器 — BCrypt 加密与校验（兼容旧 MD5+盐格式）
 */
public class PasswordEncoder {

    private static final BCryptPasswordEncoder ENCODER = new BCryptPasswordEncoder();

    public static String encode(String rawPassword) {
        return ENCODER.encode(rawPassword);
    }

    public static boolean matches(String rawPassword, String encodedPassword) {
        if (encodedPassword == null || rawPassword == null) {
            return false;
        }
        // 先 bcrypt 匹配
        if (ENCODER.matches(rawPassword, encodedPassword)) {
            return true;
        }
        // 降级 MD5 校验（旧格式）
        return matchesMd5(rawPassword, encodedPassword);
    }

    /**
     * 降级 MD5 校验（旧格式兼容）
     */
    private static boolean matchesMd5(String rawPassword, String encodedPassword) {
        if (encodedPassword == null || rawPassword == null) return false;
        if (!encodedPassword.contains("@")) return false;
        String[] arr = encodedPassword.split("@");
        String salt = arr[0];
        String expectedHash = DigestUtils.md5DigestAsHex(
                (rawPassword + salt).getBytes(StandardCharsets.UTF_8));
        return encodedPassword.equals(salt + "@" + expectedHash);
    }
}
