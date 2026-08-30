package com.hmdp.agent.prompt.builder;

/**
 * 模板常量。
 * <p>
 * 与 ExecutionPromptBuilder 分离，便于单测和后续国际化。
 * 执行 Prompt 主模板已外置到 {@code resources/prompts/agent.prompt.subagent.execution.txt}（Langfuse 为源），
 * 本类只保留解析侧 wire-format 常量与段落格式，由 Builder 以 {@code {{var}}} 注入模板。
 * </p>
 */
public final class TemplateConstants {

    private TemplateConstants() {}

    /** JSON 数据快照的开始标记 */
    public static final String SNAPSHOT_BEGIN = "===DATA_SNAPSHOT===";

    /** JSON 数据快照的结束标记 */
    public static final String SNAPSHOT_END = "===DATA_SNAPSHOT_END===";

    /** 单个 data 值的最大字符数 */
    public static final int RAW_DATA_MAX_LENGTH = 500;

    /** 传给子 Agent 的"AI 已有回复"（currentResponse）最大字符数 */
    public static final int CURRENT_RESPONSE_MAX_LENGTH = 400;

    /** 单条任务描述的格式 */
    public static final String TASK_DESC_FORMAT = "任务 %d: %s（%s）";

    /** 参数约束的格式 */
    public static final String PARAM_CONSTRAINT_FORMAT = "  工具 %s：";

    /** 单个参数的约束格式 */
    public static final String PARAM_VALUE_FORMAT = "    %s 的值已由系统设定为 \"%s\"，你无权修改该值，调用时原样使用";

    /** 摘要分隔符 */
    public static final String HISTORY_SEPARATOR = "；";
}
