package com.hmdp.agent.honesty.gate;

/**
 * summary 中被抽取出的"可断言事实"（反编造 L3）。
 *
 * @param raw   原文片段
 * @param kind  断言类型
 * @param token 归一化后的数值标记（去千分位），如 "123"
 */
public record Claim(String raw, ClaimKind kind, String token) {
}
