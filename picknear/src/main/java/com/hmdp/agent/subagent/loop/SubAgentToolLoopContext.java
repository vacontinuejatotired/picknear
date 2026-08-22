package com.hmdp.agent.subagent.loop;

import com.hmdp.agent.config.SubTaskProperties;
import com.hmdp.agent.prompt.PromptService;
import com.hmdp.agent.subagent.model.SubTaskPlan;
import org.springframework.ai.tool.ToolCallback;

import java.util.List;
import java.util.Map;

/**
 * @deprecated 请使用 {@link ToolLoopContext}。
 * 本类保留仅为编译兼容，将在下一批次删除。
 */
@Deprecated(forRemoval = true)
public record SubAgentToolLoopContext(
        List<ToolCallback> callbacks,
        String systemText,
        String initialPrompt,
        SubTaskPlan plan,
        PromptService promptService,
        Map<String, Object> toolContext,
        SubTaskProperties props) {

    public SubAgentToolLoopContext(ToolLoopContext ctx) {
        this(ctx.callbacks(), ctx.systemText(), ctx.initialPrompt(), ctx.plan(),
             ctx.promptService(), ctx.toolContext(), ctx.props());
    }

    public ToolLoopContext toToolLoopContext() {
        return new ToolLoopContext(callbacks, systemText, initialPrompt, plan,
                                  promptService, toolContext, props);
    }
}
