package com.hmdp.agent.prompt;

/**
 * 提示词键常量（单一事实源）。
 * <p>
 * 每个 key 同时是：Langfuse Prompt 名 + 内置模板资源文件名（{@code prompts/{key}.txt}）。
 * </p>
 */
public final class PromptKeys {

    private PromptKeys() {}

    /** 主对话系统提示词 */
    public static final String SYSTEM_MAIN = "agent.system.main";

    /** 子 Agent 系统提示词 */
    public static final String SYSTEM_SUBAGENT = "agent.system.subagent";

    /** 任务规划系统提示词 */
    public static final String SYSTEM_PLANNER = "agent.system.planner";

    /** 任务规划用户模板 */
    public static final String PLANNER_USER = "agent.prompt.planner";

    /** 子 Agent 执行模板 */
    public static final String SUBAGENT_EXECUTION = "agent.prompt.subagent.execution";

    /** 回退路径聚合结论模板 */
    public static final String TASK_MERGE = "agent.prompt.task.merge";

    /** 工具描述模板键：agent.tool.{toolName} */
    public static String tool(String toolName) {
        return "agent.tool." + toolName;
    }
}
