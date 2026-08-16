package com.hmdp.enums;

import lombok.Getter;

/**
 * 统一业务状态码（HTTP 风格粗粒度）。
 * <p>
 * 约定：写入 {@code Result.code}，可选字段（null 时不序列化，兼容旧前端）。
 * 内部判定码（如 Lua 返回码 SeckillOrderCode/TokenRefreshCode）不属于响应状态码体系，另行使用。
 * </p>
 */
@Getter
public enum ErrorCode {

    SUCCESS(200, "成功"),

    // 客户端错误 (4xx)
    BAD_REQUEST(400, "请求参数错误"),
    UNAUTHORIZED(401, "未登录或登录已过期"),
    FORBIDDEN(403, "无权访问"),
    NOT_FOUND(404, "资源不存在"),
    CONFLICT(409, "请求冲突"),
    TOO_MANY_REQUESTS(429, "请求过于频繁"),

    // 服务端错误 (5xx)
    SERVER_ERROR(500, "服务器异常"),
    SERVICE_UNAVAILABLE(503, "服务暂不可用");

    private final int code;
    private final String message;

    ErrorCode(int code, String message) {
        this.code = code;
        this.message = message;
    }
}
