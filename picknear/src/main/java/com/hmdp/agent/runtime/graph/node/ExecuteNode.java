package com.hmdp.agent.runtime.graph.node;

import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.action.AsyncNodeAction;
import org.springframework.stereotype.Component;

import java.util.Map;

import static com.alibaba.cloud.ai.graph.action.AsyncNodeAction.node_async;

/**
 * Graph 工具执行节点，保留给项目作者实现。
 *
 * <p>该节点是模型的单轮工具回合入口。实现时可以按以下状态读写契约完成：</p>
 * <ul>
 *   <li>读取 {@code messages}、{@code systemText}、{@code history} 组装 Prompt；</li>
 *   <li>读取 {@code userId}、{@code conversationId} 注入 ToolContext；</li>
 *   <li>调用模型并判断是否产生 tool calls；</li>
 *   <li>执行工具后更新 {@code messages}、{@code toolRounds}、
 *       {@code toolCallCount}、{@code modelCallCount}；</li>
 *   <li>用 {@code pendingToolCalls} 和 {@code stopReason} 告诉
 *       {@link VerifyNode} 继续循环还是最终收口。</li>
 * </ul>
 *
 * <p>当前只保留节点契约，不提供实现，避免覆盖作者自己的学习过程。</p>
 */
@Component
public class ExecuteNode {

    public String name() {
        return GraphNodeNames.EXECUTE;
    }

    public AsyncNodeAction action() {
        return node_async(this::apply);
    }

    private Map<String, Object> apply(OverAllState state) {
        throw new UnsupportedOperationException(
                "ExecuteNode 尚未实现，请按节点状态契约完成工具执行。"
        );
    }
}
