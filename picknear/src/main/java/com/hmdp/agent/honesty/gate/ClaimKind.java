package com.hmdp.agent.honesty.gate;

/**
 * 断言类型（反编造 L3，ClaimExtractor 输出）。
 * <p>P0 只消费 {@link #STATS_COUNT}（平台统计类断言，Detector A 高精度窄规则）；其余留 Detector B 泛化。</p>
 */
public enum ClaimKind {
    /** 平台统计断言（"共/当前共有 N 篇|家|位|条|个用户…"） */
    STATS_COUNT,
    /** 一般数字（Detector B） */
    NUMBER,
    /** 实体 ID 引用（Detector B） */
    ENTITY_ID
}
