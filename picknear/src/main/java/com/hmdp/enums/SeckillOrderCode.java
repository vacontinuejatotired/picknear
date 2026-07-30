package com.hmdp.enums;

import lombok.Getter;

/**
 * 秒杀订单结果枚举 — 定义秒杀成功/库存不足/重复下单等状态码
 */
@Getter
public enum SeckillOrderCode {

    // 成功
    SUCCESS(200L, "下单成功"),

    // 库存相关错误 (50x)
    INSUFFICIENT_STOCK(501L, "库存不足"),
    STOCK_NOT_FOUND(502L, "该优惠券无库存记录"),

    // 用户相关错误 (51x)
    REPEAT_ORDER(511L, "用户已下单，请勿重复下单"),

    // 订单相关错误 (52x)
    ORDER_CREATE_FAILED(521L, "订单创建失败");

    private final Long code;  // int → Long
    private final String message;

    SeckillOrderCode(Long code, String message) {
        this.code = code;
        this.message = message;
    }

    /**
     * 根据状态码获取枚举
     */
    public static SeckillOrderCode fromCode(Long code) {  // int → Long
        if (code == null) {
            return null;
        }
        for (SeckillOrderCode value : values()) {
            if (value.code.equals(code)) {  // == → equals()
                return value;
            }
        }
        return null;
    }

    /**
     * 判断是否成功
     */
    public boolean isSuccess() {
        return this == SUCCESS;
    }

    /**
     * 获取默认错误消息
     */
    public static String getDefaultMessage(Long code) {  // int → Long
        SeckillOrderCode enumCode = fromCode(code);
        return enumCode != null ? enumCode.getMessage() : "未知错误：" + code;
    }
}