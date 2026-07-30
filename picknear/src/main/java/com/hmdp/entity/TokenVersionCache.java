package com.hmdp.entity;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Token版本号本地缓存实体 — 两级验证用（Caffeine快速拒绝 + Redis最终校验）
 */
@Data
@AllArgsConstructor
@NoArgsConstructor
public class TokenVersionCache {
    private Long userId;
    private Long version;
    private Integer status;
}
