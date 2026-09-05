package com.hmdp.agent.honesty;

/**
 * 数据意图类别（反编造 L1，DataIntentClassifier 输出）。
 * <p>
 * NONE = 无数据查询意图（闲聊/非平台数据）；其余任一均视为"该问题涉及平台数据/能力，
 * Phase1 不得裸答、应走工具规划"。类别仅用于路由倾向与观测，不承载"是否有工具可查"
 * （可查性由规划阶段 ToolIntentTree 决定，空计划走诚实兜底）。
 * </p>
 */
public enum DataIntent {

    /** 平台统计/计数（共有多少店/用户/博客） */
    PLATFORM_STATS,
    /** 店铺/商家相关 */
    SHOP,
    /** 博客/笔记/点评 */
    BLOG,
    /** 用户/作者资料 */
    USER,
    /** 优惠券/订单 */
    VOUCHER,
    /** 天气 */
    WEATHER,
    /** 当前日期/时间 */
    CLOCK,
    /** 我的（自己名下）实体 */
    MY_ENTITY,
    /** 无数据查询意图 */
    NONE;

    public boolean isDataQuery() {
        return this != NONE;
    }
}
