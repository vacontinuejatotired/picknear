package com.hmdp.utils.cache;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * Redis 数据包装 — 带逻辑过期时间的缓存数据封装
 */
@Data
public class RedisData {
    private LocalDateTime expireTime;
    private Object data;
}
