package com.hmdp.agent.honesty;

/**
 * 反编造机制在 AgentContext.attributes 上使用的扩展 key（不新增 AgentContext 字段）。
 * <p>实现方与消费方经本常量契约，避免魔法字符串漂移。</p>
 */
public final class HonestyKeys {

    /** 输入侧数据意图标记（值为 {@link DataIntent}）——PromptHook 打标，AfterAiHook/编排层消费 */
    public static final String ATTR_DATA_INTENT = "honesty.dataIntent";

    /** PLANNING 时替换传给规划器的 seed（避免 Phase1 若已编数字继续污染子 Agent） */
    public static final String ATTR_PLAN_SEED_OVERRIDE = "honesty.planSeedOverride";

    /** 数据类任务的种子替换文本（中性，诱导子 Agent 只基于工具结果作答） */
    public static final String PLAN_SEED_TEXT = "（数据查询任务，请基于工具结果作答）";

    private HonestyKeys() {
    }
}
