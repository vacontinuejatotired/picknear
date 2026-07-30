package com.hmdp.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Token 校验结果 — 封装 JWT 解析 + 版本校验结果，供拦截器使用
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ValidationResult {
    /** Token 是否有效 */
    private boolean valid;
    /** 用户 ID（解析自 JWT claims） */
    private Long userId;
    /** Token 中携带的版本号 */
    private Long version;
    /** 是否需要刷新（临期或已过期） */
    private boolean needsRefresh;
}
