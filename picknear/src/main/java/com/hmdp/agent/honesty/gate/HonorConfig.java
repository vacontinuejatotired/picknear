package com.hmdp.agent.honesty.gate;

/**
 * 断言闸处置配置（反编造 L3，值对象）。
 *
 * @param action 处置档位（默认 OBSERVE 由调用方从配置解析）
 */
public record HonorConfig(HonorAction action) {

    public static HonorConfig observe() {
        return new HonorConfig(HonorAction.OBSERVE);
    }
}
