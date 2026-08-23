package com.hmdp.agent.subagent.prompt;

import java.util.Map;

/**
 * @deprecated 请使用 {@link com.hmdp.agent.prompt.builder.ExecutionPromptBuilder}。
 * 本类保留仅为编译兼容，将在下一批次删除。
 */
@Deprecated(forRemoval = true)
public final class ExecutionPromptBuilder {

    private ExecutionPromptBuilder() {}

    /** @deprecated 使用 {@link com.hmdp.agent.prompt.builder.ExecutionPromptBuilder#buildVariables} */
    @Deprecated
    public static Map<String, String> buildVariables(com.hmdp.agent.execution.model.ExecutionInput plan) {
        return com.hmdp.agent.prompt.builder.ExecutionPromptBuilder.buildVariables(plan);
    }

    /** @deprecated 使用 {@link com.hmdp.agent.prompt.builder.ExecutionPromptBuilder#truncateResult} */
    @Deprecated
    public static String truncateResult(String result) {
        return com.hmdp.agent.prompt.builder.ExecutionPromptBuilder.truncateResult(result);
    }
}
