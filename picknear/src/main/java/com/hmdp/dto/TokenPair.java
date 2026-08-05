package com.hmdp.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Token 对 — 登录/刷新时返回的完整凭证，由 AuthService 生成，不含 HTTP 细节
 */
@Data
@AllArgsConstructor
@NoArgsConstructor
public class TokenPair {
    @Schema(description = "访问令牌（JWT）")
    private String accessToken;
    @Schema(description = "刷新令牌（UUID）")
    private String refreshToken;
    @Schema(description = "令牌版本号")
    private Long version;
}
