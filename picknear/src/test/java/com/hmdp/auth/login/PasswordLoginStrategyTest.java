package com.hmdp.auth.login;

import com.hmdp.auth.dto.LoginFormDTO;
import com.hmdp.auth.dto.TokenPair;
import com.hmdp.auth.token.TokenService;
import com.hmdp.user.account.UserAccountService;
import com.hmdp.user.entity.User;
import com.hmdp.utils.security.PasswordEncoder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * PasswordLoginStrategy — 密码登录策略测试（P2-S5）。
 * 覆盖：supports 判定、新用户自动注册、密码错误失败计数锁定、MD5→bcrypt 升级。
 */
@ExtendWith(MockitoExtension.class)
class PasswordLoginStrategyTest {

    @Mock
    private UserAccountService userAccountService;

    @Mock
    private TokenService tokenService;

    @Mock
    private StringRedisTemplate stringRedisTemplate;

    @Mock
    private ValueOperations<String, String> valueOps;

    private PasswordLoginStrategy strategy;

    @BeforeEach
    void setUp() {
        strategy = new PasswordLoginStrategy();
        ReflectionTestUtils.setField(strategy, "userAccountService", userAccountService);
        ReflectionTestUtils.setField(strategy, "tokenService", tokenService);
        ReflectionTestUtils.setField(strategy, "stringRedisTemplate", stringRedisTemplate);
    }

    private LoginFormDTO passwordForm(String password) {
        LoginFormDTO form = new LoginFormDTO();
        form.setPhone("13800000000");
        form.setPassword(password);
        return form;
    }

    private User existingUser(String passwordHash) {
        return new User().setId(42L).setPhone("13800000000").setPassword(passwordHash);
    }

    @Test
    void supports_should_match_password_form_only() {
        assertThat(strategy.supports(passwordForm("abc12345"))).as("有密码应命中密码策略").isTrue();
        assertThat(strategy.supports(new LoginFormDTO())).as("无密码不应命中").isFalse();
    }

    @Test
    void login_should_auto_register_new_user() {
        when(userAccountService.queryByPhone("13800000000")).thenReturn(null);
        User created = new User().setId(42L).setPhone("13800000000");
        when(userAccountService.createUser(eq("13800000000"), anyString())).thenReturn(created);
        when(tokenService.generateTokenPair(42L)).thenReturn(new TokenPair("at", "rt", 1L));

        TokenPair pair = strategy.login(passwordForm("abc12345"));

        assertThat(pair.getAccessToken()).as("新用户应直接发 Token").isEqualTo("at");
        verify(userAccountService).createUser(eq("13800000000"), anyString());
    }

    @Test
    void login_should_lock_after_max_failures() {
        when(userAccountService.queryByPhone("13800000000")).thenReturn(existingUser("$2a$10$abcdefghijklmnopqrstuv"));
        when(stringRedisTemplate.hasKey("login:lock:13800000000")).thenReturn(false);
        when(stringRedisTemplate.opsForValue()).thenReturn(valueOps);
        when(valueOps.increment("login:fail:count:13800000000")).thenReturn(10L);

        try (MockedStatic<PasswordEncoder> encoder = mockStatic(PasswordEncoder.class)) {
            encoder.when(() -> PasswordEncoder.matches("wrong-pass", "$2a$10$abcdefghijklmnopqrstuv"))
                    .thenReturn(false);

            assertThatThrownBy(() -> strategy.login(passwordForm("wrong-pass")))
                    .as("连续失败达上限应抛锁定异常")
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("锁定");
        }
        verify(valueOps).set(eq("login:lock:13800000000"), anyString(), anyLong(), any(TimeUnit.class));
    }

    @Test
    void login_should_upgrade_md5_password_to_bcrypt() {
        when(userAccountService.queryByPhone("13800000000")).thenReturn(existingUser("oldmd5hash"));
        when(stringRedisTemplate.hasKey("login:lock:13800000000")).thenReturn(false);
        when(tokenService.generateTokenPair(42L)).thenReturn(new TokenPair("at", "rt", 1L));

        try (MockedStatic<PasswordEncoder> encoder = mockStatic(PasswordEncoder.class)) {
            encoder.when(() -> PasswordEncoder.matches("abc12345", "oldmd5hash")).thenReturn(true);
            encoder.when(() -> PasswordEncoder.encode("abc12345")).thenReturn("$2a$10$newbcrypthash");

            strategy.login(passwordForm("abc12345"));

            // 旧 MD5 密码登录成功 → 自动升级为 bcrypt 并更新
            verify(userAccountService).updatePassword(eq(existingUser("oldmd5hash")), eq("$2a$10$newbcrypthash"));
        }
    }
}
