package com.hmdp.agent.honesty.gate;

/**
 * 输出断言闸的处置档位（反编造 L3）。
 * <p>
 * 默认 {@link #OBSERVE}（只观测校准）→ {@link #APPEND_DISCLAIMER}（附注）→
 * {@link #RECHECK}/{@link #DROP}（演进，P1/P2，默认不启用）。
 * </p>
 */
public enum HonorAction {
    /** 整闸关闭（不抽取不拦截） */
    OFF,
    /** 只记录命中（日志/观测），不改回复 —— 校准用默认 */
    OBSERVE,
    /** 命中时在 summary 末尾附"数据未核实"说明，不重答 */
    APPEND_DISCLAIMER,
    /** 命中时 strict rewrite 重答一次（P1，max-recheck 硬顶） */
    RECHECK,
    /** 物理丢弃：整条作废重发兜底文案（P1 校准后，默认关） */
    DROP
}
