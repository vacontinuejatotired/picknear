package com.hmdp.agent.util;

/**
 * SSE 事件常量统一收口。
 * <p>
 * 所有 stage、toolStatus、progress text 不得硬编码，必须引用此处的常量。
 * 前端 AiChat.vue 依赖 stage: "merging" 和 "confirm"，修改需同步前端。
 * </p>
 */
public final class SseEventConstants {

    private SseEventConstants() {}

    // ════════════════════════════════════════════════════════════
    // Event stages（progressEvent 的 stage 参数）
    // ════════════════════════════════════════════════════════════

    /** 规划完成 */
    public static final String STAGE_PLANNING = "planning";

    /** 执行中（子 Agent 调用前） */
    public static final String STAGE_EXECUTING = "executing";

    /** 汇总中（parseResult 完成后） */
    public static final String STAGE_MERGING = "merging";

    /** 需用户确认 */
    public static final String STAGE_CONFIRM = "confirm";

    /** 工具步骤（stepEvent 的固定 stage） */
    public static final String STAGE_STEP = "step";

    // ════════════════════════════════════════════════════════════
    // Tool call status（stepEvent 的 status 参数，与 SubTaskStatus 对应）
    // ════════════════════════════════════════════════════════════

    /** 工具执行中 */
    public static final String TOOL_RUNNING = "RUNNING";

    /** 工具执行成功 */
    public static final String TOOL_COMPLETED = "COMPLETED";

    /** 工具执行失败 */
    public static final String TOOL_FAILED = "FAILED";

    // ════════════════════════════════════════════════════════════
    // Progress text（给用户看的描述文案）
    // ════════════════════════════════════════════════════════════

    /** 规划完成文案前缀，后面拼接工具名列表 */
    public static final String TEXT_PLANNING_PREFIX = "规划完成：需要执行 ";

    /** 执行中文案前缀，后面拼接任务数 */
    public static final String TEXT_EXECUTING_PREFIX = "正在执行 ";

    /** 执行中文案后缀，前面拼接任务数 */
    public static final String TEXT_EXECUTING_SUFFIX = " 个任务...";

    /** 汇总中文案 */
    public static final String TEXT_MERGING = "数据汇总完成，生成回答...";

    /** 回退路径汇总中文案 */
    public static final String TEXT_MERGING_FALLBACK = "正在生成结论...";

    /** 结论完成文案 */
    public static final String TEXT_MERGING_DONE = "结论生成完成";
}
