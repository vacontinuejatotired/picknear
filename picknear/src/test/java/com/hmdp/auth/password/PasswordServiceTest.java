package com.hmdp.auth.password;

import com.hmdp.auth.dto.PasswordChangeDTO;
import com.hmdp.auth.dto.TokenPair;
import com.hmdp.auth.token.TokenService;
import com.hmdp.auth.verifycode.VerifyCodeService;
import com.hmdp.dto.Result;
import com.hmdp.user.account.UserAccountService;
import com.hmdp.user.dto.UserDTO;
import com.hmdp.user.entity.User;
import com.hmdp.utils.UserHolder;
import com.hmdp.utils.security.PasswordEncoder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * PasswordService — 密码服务测试（P2-S4）。
 * 覆盖：修改密码（旧密码校验/bump version 发新 Token）、重置密码（验证码/强度/未注册）。
 */
@ExtendWith(MockitoExtension.class)
class PasswordServiceTest {

    @Mock
    private UserAccountService userAccountService;

    @Mock
    private VerifyCodeService verifyCodeService;

    @Mock
    private TokenService tokenService;

    private PasswordService service;

    @BeforeEach
    void setUp() {
        service = new PasswordService();
        ReflectionTestUtils.setField(service, "userAccountService", userAccountService);
        ReflectionTestUtils.setField(service, "verifyCodeService", verifyCodeService);
        ReflectionTestUtils.setField(service, "tokenService", tokenService);
    }

    @AfterEach
    void tearDown() {
        UserHolder.remove();
    }

    private PasswordChangeDTO changeDto(String oldPwd, String newPwd) {
        PasswordChangeDTO dto = new PasswordChangeDTO();
        dto.setOldPassword(oldPwd);
        dto.setNewPassword(newPwd);
        return dto;
    }

    @Test
    void changePassword_should_reject_when_not_logged_in() {
        PasswordChangeDTO dto = changeDto("old-pass", "NewPass123");

        assertThatThrownBy(() -> service.changePassword(dto))
                .as("未登录应拒绝")
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("未登录");
    }

    @Test
    void changePassword_should_bump_version_and_return_new_token() {
        UserHolder.saveUserDTO(new UserDTO(7L, "张三", ""));
        User user = new User().setId(7L).setPassword("$2a$10$bcrypthash");
        when(userAccountService.getById(7L)).thenReturn(user);
        when(tokenService.generateTokenPair(7L)).thenReturn(new TokenPair("new-at", "new-rt", 2L));

        try (MockedStatic<PasswordEncoder> encoder = mockStatic(PasswordEncoder.class)) {
            encoder.when(() -> PasswordEncoder.matches("old-pass", "$2a$10$bcrypthash")).thenReturn(true);
            encoder.when(() -> PasswordEncoder.encode("NewPass123")).thenReturn("$2a$10$newhash");

            TokenPair pair = service.changePassword(changeDto("old-pass", "NewPass123"));

            assertThat(pair.getAccessToken()).as("改密成功应发新 Token").isEqualTo("new-at");
            verify(userAccountService).updatePassword(eq(user), eq("$2a$10$newhash"));
        }
    }

    @Test
    void resetPassword_should_reject_when_code_invalid() {
        when(verifyCodeService.consumeVerifyCode("13800000000", "123456")).thenReturn(false);

        Result result = service.resetPassword("13800000000", "123456", "NewPass123");

        assertThat(result.getSuccess()).as("验证码错应失败").isFalse();
        verify(userAccountService, never()).queryByPhone(anyString());
    }

    @Test
    void resetPassword_should_reject_when_phone_not_registered() {
        when(verifyCodeService.consumeVerifyCode("13800000000", "123456")).thenReturn(true);
        when(userAccountService.queryByPhone("13800000000")).thenReturn(null);

        Result result = service.resetPassword("13800000000", "123456", "NewPass123");

        assertThat(result.getSuccess()).as("未注册手机号应失败").isFalse();
        assertThat(result.getErrorMsg()).contains("未注册");
    }
}
