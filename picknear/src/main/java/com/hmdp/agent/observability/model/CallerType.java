package com.hmdp.agent.observability.model;

/**
 * LLM 调用方标识枚举（评审 13.3.1：取代 6 个业务类中散落的打标魔法字符串）。
 * <p>
 * 业务类调用 {@code ChatModelObservationConventionConfig.mark(CallerType.X[, task])} 打标，
 * 展示层（convention 的 generation 命名）按其 {@link #id()} 拼前缀——标识有单一事实源；
 * 是否拼入 generation 名由观测后端能力决定（capabilities 驱动），与打标调用本身解耦。
 * </p>
 */
public enum CallerType {

    /** Phase1 主对话流式/同步调用 */
    PHASE1("phase1"),
    /** 子任务规划（plan）调用 */
    PLANNER("planner"),
    /** 回退路径 LLM_REASON 聚合调用 */
    LLM_REASON("llm-reason"),
    /** 子代理工具循环主建模调用（携带剩余任务工具名清单） */
    SUBAGENT_EXEC("subagent-exec"),
    /** 工具结果压缩调用（携带被压缩的工具名） */
    SUBAGENT_COMPRESS("subagent-compress");

    private final String id;

    CallerType(String id) {
        this.id = id;
    }

    /** 展示/拼接用标识（与历史 generation 名前缀一致，保持既有观测口径不回退） */
    public String id() {
        return id;
    }
}