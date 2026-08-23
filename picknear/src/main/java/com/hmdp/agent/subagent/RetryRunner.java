package com.hmdp.agent.subagent;

/**
 * @deprecated 请使用 {@link com.hmdp.agent.execution.RetryRunner}。
 * 本类保留仅为编译兼容，将在下一批次删除。
 */
@Deprecated(forRemoval = true)
public class RetryRunner extends com.hmdp.agent.execution.RetryRunner {

    public RetryRunner(com.hmdp.agent.subagent.loop.ToolExecutionStrategy toolLoop,
                       com.hmdp.agent.prompt.PromptService promptService) {
        super(toolLoop, promptService);
    }
}
