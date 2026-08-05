package com.hmdp.dto;

import lombok.Data;

/**
 * Lua脚本执行结果包装 — 用于 Seckill Lua 脚本的多返回值解析
 */
@Data
public class LuaResult {
    private Integer code;

}
