package com.hmdp.agent.subagent.prompt;

/**
 * 子 Agent Prompt 模板常量。
 * <p>
 * 与 SubAgentPromptBuilder 分离，便于单测和后续国际化。
 * EXECUTION_PROMPT 使用 %s 占位符，构建时由 Builder 注入 SNAPSHOT_BEGIN/END 等常量。
 * </p>
 */
public final class SubAgentPromptTemplate {

    private SubAgentPromptTemplate() {}

    /** JSON 数据快照的开始标记 */
    public static final String SNAPSHOT_BEGIN = "===DATA_SNAPSHOT===";

    /** JSON 数据快照的结束标记 */
    public static final String SNAPSHOT_END = "===DATA_SNAPSHOT_END===";

    /** 单个 data 值的最大字符数 */
    public static final int RAW_DATA_MAX_LENGTH = 500;

    /**
     * 执行 Prompt 的整体模板。
     * <p>
     * 占位符顺序：
     * %s = userInput
     * %s = currentResponse
     * %s = historySummary
     * %s = paramConstraints
     * %s = tasksDesc
     * %s = SNAPSHOT_BEGIN
     * %s = RAW_DATA_MAX_LENGTH
     * %s = SNAPSHOT_END
     * %s = RAW_DATA_MAX_LENGTH（尾部提示）
     * </p>
     */
    public static final String EXECUTION_PROMPT = """
            你需要根据以下任务计划，调用工具获取数据，并给用户一段完整的中文回答。

            ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
            【上下文】
            用户问题：%s

            AI 已有回复：%s

            历史执行摘要（已完成工具的结果摘要）：
            %s

            ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
            【参数约束—你无权修改参数值】

            %s

            以下是你必须严格执行的工具参数。这些参数的值已由系统设定，你无权修改，调用工具时必须原样使用。

            ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
            【本轮待执行任务】
            %s

            ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
            要求：
            1. 逐个调用所需工具，每次调一个，拿到结果后再调下一个
            2. 如果工具返回了数据，理解数据含义并纳入回答
            3. 如果工具失败，在回答中说明原因，继续执行其他工具
            4. 所有工具执行完毕后，用中文给出对用户的完整回答
            5. 不要编造工具没有返回的数据

            【重要—回复格式要求】
            在所有工具执行完毕后、中文回答之后，在回复末尾附加以下 JSON 数据快照（不要 markdown 代码块标记）：

            %s
            {
              "toolName1": {
                "status": "ok",
                "data": <工具返回的主要数据，不超过%s字>
              },
              "toolName2": {
                "status": "error",
                "message": "错误描述"
              }
            }
            %s

            注意：data 字段的值请控制在 %s 字以内，超出部分会被系统自动截断。
            """;

    /** 单条任务描述的格式 */
    public static final String TASK_DESC_FORMAT = "任务 %d: %s（%s）";

    /** 参数约束的格式 */
    public static final String PARAM_CONSTRAINT_FORMAT = "  工具 %s：";

    /** 单个参数的约束格式 */
    public static final String PARAM_VALUE_FORMAT = "    %s 的值已由系统设定为 \"%s\"，你无权修改该值，调用时原样使用";

    /** 摘要分隔符 */
    public static final String HISTORY_SEPARATOR = "；";
}
