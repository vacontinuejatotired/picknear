package com.hmdp.dto;

import lombok.Data;

/**
 * 密码修改请求 DTO
 */
@Data
public class PasswordChangeDTO {
    private String oldPassword;
    private String newPassword;
}
