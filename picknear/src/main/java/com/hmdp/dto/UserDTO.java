package com.hmdp.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 用户摘要DTO — 用于列表展示和会话缓存，不含敏感信息
 */
@Data
@AllArgsConstructor
@NoArgsConstructor
public class UserDTO {
    @Schema(description = "用户ID")
    private Long id;
    @Schema(description = "昵称")
    private String nickName;
    @Schema(description = "头像URL")
    private String icon;
}
