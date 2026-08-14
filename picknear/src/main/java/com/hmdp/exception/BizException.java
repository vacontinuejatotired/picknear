package com.hmdp.exception;

import com.hmdp.enums.ErrorCode;
import lombok.Getter;

/**
 * 业务异常 — 携带统一状态码，由 {@link WebExceptionAdvice} 转为 Result。
 * <p>
 * 需要给前端区分错误类型时抛此异常（如越权访问、业务校验失败），
 * 不要再用散落的 {@code Result.fail("文案")}。
 * </p>
 */
@Getter
public class BizException extends RuntimeException {

    private final int code;

    public BizException(ErrorCode errorCode) {
        super(errorCode.getMessage());
        this.code = errorCode.getCode();
    }

    public BizException(ErrorCode errorCode, String message) {
        super(message);
        this.code = errorCode.getCode();
    }

    public BizException(int code, String message) {
        super(message);
        this.code = code;
    }
}
