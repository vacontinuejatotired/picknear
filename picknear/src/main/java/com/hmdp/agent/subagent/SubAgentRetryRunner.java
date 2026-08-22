package com.hmdp.agent.subagent;

import com.hmdp.agent.subagent.loop.ToolExecutionStrategy;
import com.hmdp.agent.prompt.PromptService;

/**
 * @deprecated 请使用 {@link RetryRunner}。
 * 本类保留仅为编译兼容，将在下一批次删除。
 */
@Deprecated(forRemoval = true)
public class SubAgentRetryRunner extends RetryRunner {

    public SubAgentRetryRunner(ToolExecutionStrategy toolLoop, PromptService promptService) {
        super(toolLoop, promptService);
    }
}
