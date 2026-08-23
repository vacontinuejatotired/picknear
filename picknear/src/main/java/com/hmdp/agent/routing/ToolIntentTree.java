package com.hmdp.agent.routing;

import com.hmdp.agent.tool.ToolRegistry;

/**
 * @deprecated 请使用 {@link com.hmdp.agent.plan.intent.ToolIntentTree}。
 * 本类保留仅为编译兼容，将在下一批次删除。
 */
@Deprecated(forRemoval = true)
public class ToolIntentTree extends com.hmdp.agent.plan.intent.ToolIntentTree {

    public ToolIntentTree(ToolRegistry toolRegistry) {
        super(toolRegistry);
    }
}
