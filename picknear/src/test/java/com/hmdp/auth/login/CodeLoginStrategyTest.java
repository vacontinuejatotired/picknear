package com.hmdp.auth.login;

import com.hmdp.auth.dto.LoginFormDTO;
import com.hmdp.auth.dto.TokenPair;
import com.hmdp.auth.token.TokenService;
import com.hmdp.auth.verifycode.VerifyCodeService;
import com.hmdp.user.account.UserAccountService;
import com.hmdp.user.entity.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

/**
 * CodeLoginStrategy — 验证码登录策略测试（P2-S5）。
 * 覆盖：supports 判定、验证码消费失败拒绝、验证码登录自动注册。
 */
@ExtendWith(MockitoExtension.class)
class CodeLoginStrategyTest {

    @Mock
    private VerifyCodeService verifyCodeService;

    @Mock
    private UserAccountService userAccountService;

    @Mock
    private TokenService tokenService;

    private CodeLoginStrategy strategy;

    @BeforeEach
    void setUp() {
        strategy = new CodeLoginStrategy();
        ReflectionTestUtils.setField(strategy, "verifyCodeService", verifyCodeService);
        ReflectionTestUtils.setField(strategy, "userAccountService", userAccountService);
        ReflectionTestUtils.setField(strategy, "tokenService", tokenService);
    }

    @Test
    void supports_should_match_code_form_only() {
        LoginFormDTO codeForm = new LoginFormDTO();
        LoginFormDTO passwordForm = new LoginFormDTO();
        passwordForm.setPassword("abc12345");

        assertThat(strategy.supports(codeForm)).as("无密码应命中验证码策略").isTrue();
        assertThat(strategy.supports(passwordForm)).as("有密码不应命中").isFalse();
    }

    @Test
    void login_should_reject_when_code_consumption_fails() {
        LoginFormDTO form = new LoginFormDTO();
        form.setPhone("13800000000");
        form.setCode("123456");
        when(verifyCodeService.consumeVerifyCode("13800000000", "123456")).thenReturn(false);

        assertThatThrownBy(() -> strategy.login(form))
                .as("验证码错误应拒绝登录")
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("验证码错误");
        verify(userAccountService, never()).queryByPhone(anyString());
    }

    @Test
    void login_should_auto_register_when_user_not_exists() {
        LoginFormDTO form = new LoginFormDTO();
        form.setPhone("13800000000");
        form.setCode("123456");
        when(verifyCodeService.consumeVerifyCode("13800000000", "123456")).thenReturn(true);
        when(userAccountService.queryByPhone("13800000000")).thenReturn(null);
        User created = new User().setId(42L).setPhone("13800000000");
        when(userAccountService.createUser("13800000000", null)).thenReturn(created);
        when(tokenService.generateTokenPair(42L)).thenReturn(new TokenPair("at", "rt", 1L));

        TokenPair pair = strategy.login(form);

        assertThat(pair.getAccessToken()).as("自动注册后应发 Token").isEqualTo("at");
        verify(userAccountService).createUser("13800000000", null);
    }
}
