package com.hmdp.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 统一响应体 — 所有API返回此对象
 * success=true 表示成功，errorMsg 为失败原因，data 为负载数据，total 为分页总数（暂未使用）
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

    public static Result ok(){
        return new Result(true, null, null, null);
    }
    public static Result ok(Object data){
        return new Result(true, null, data, null);
    }
    public static Result ok(List<?> data, Long total){
        return new Result(true, null, data, total);
    }
    public static Result fail(String errorMsg){
        return new Result(false, errorMsg, null, null);
    }
}
