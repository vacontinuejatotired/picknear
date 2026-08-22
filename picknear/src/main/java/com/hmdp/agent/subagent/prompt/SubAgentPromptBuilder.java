package com.hmdp.agent.subagent.prompt;

import java.util.Map;

/**
 * @deprecated 请使用 {@link ExecutionPromptBuilder}。
 * 本类保留仅为编译兼容，将在下一批次删除。
 */
@Deprecated(forRemoval = true)
public final class SubAgentPromptBuilder {

    private SubAgentPromptBuilder() {}

    /** @deprecated 使用 {@link ExecutionPromptBuilder#buildVariables} */
    @Deprecated
    public static Map<String, String> buildVariables(com.hmdp.agent.subagent.model.SubTaskPlan plan) {
        return ExecutionPromptBuilder.buildVariables(plan);
    }

    /** @deprecated 使用 {@link ExecutionPromptBuilder#truncateResult} */
    @Deprecated
    public static String truncateResult(String result) {
        return ExecutionPromptBuilder.truncateResult(result);
    }
}
