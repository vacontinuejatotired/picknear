package com.hmdp.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.hmdp.enums.ErrorCode;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 统一响应体 — 所有API返回此对象
 * success=true 表示成功，errorMsg 为失败原因，data 为负载数据，total 为分页总数（暂未使用）
 * code 为统一业务状态码（可选字段：null 时不序列化，兼容旧前端）
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class Result {
    @Schema(description = "是否成功")
    private Boolean success;
    @Schema(description = "错误信息")
    private String errorMsg;
    @Schema(description = "响应数据")
    private Object data;
    @Schema(description = "总条数（分页时使用）")
    private Long total;
    @Schema(description = "统一业务状态码（200 成功；4xx 客户端错误；5xx 服务端错误；null 表示未指定）")
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private Integer code;

    public static Result ok(){
        return new Result(true, null, null, null, 200);
    }
    public static Result ok(Object data){
        return new Result(true, null, data, null, 200);
    }
    public static Result ok(List<?> data, Long total){
        return new Result(true, null, data, total, 200);
    }
    /**
     * 兼容旧调用：不设置 code（序列化时省略该字段，响应与旧版完全一致）。
     * 新代码请使用 {@link #fail(ErrorCode)} / {@link #fail(ErrorCode, String)}。
     */
    public static Result fail(String errorMsg){
        return new Result(false, errorMsg, null, null, null);
    }
    public static Result fail(ErrorCode errorCode){
        return new Result(false, errorCode.getMessage(), null, null, errorCode.getCode());
    }
    public static Result fail(ErrorCode errorCode, String message){
        return new Result(false, message, null, null, errorCode.getCode());
    }
    public static Result fail(int code, String message){
        return new Result(false, message, null, null, code);
    }
}
