package com.hmdp.dto;

import lombok.Data;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 登录表单DTO — 手机号+验证码 或 手机号+密码
 */
@Data
public class LoginFormDTO {
    @Schema(description = "手机号")
    private String phone;
    @Schema(description = "验证码")
    private String code;
    @Schema(description = "密码")
    private String password;
}
