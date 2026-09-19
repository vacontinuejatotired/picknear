package com.hmdp.agent.runtime.graph;

import com.alibaba.cloud.ai.graph.CompileConfig;
import com.alibaba.cloud.ai.graph.CompiledGraph;
import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.alibaba.cloud.ai.graph.StateGraph;
import com.alibaba.cloud.ai.graph.exception.GraphStateException;
import com.alibaba.cloud.ai.graph.state.strategy.ReplaceStrategy;
import com.hmdp.agent.access.AgentCommand;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import static com.alibaba.cloud.ai.graph.StateGraph.END;
import static com.alibaba.cloud.ai.graph.StateGraph.START;
import static com.alibaba.cloud.ai.graph.action.AsyncNodeAction.node_async;

/**
 * Alibaba Graph 最小工厂。
 *
 * <p>当前只提供运行时骨架：输入经过一个占位节点后返回。后续规划、工具执行、
 * 审批和 checkpoint 都在这张图上逐步扩展。</p>
 */
@Component
public class AgentGraphFactory {

    public static final String OUTPUT = "output";

    private final CompiledGraph graph;

    public AgentGraphFactory() {
        try {
            this.graph = buildGraph();
        } catch (GraphStateException e) {
            throw new IllegalStateException("Agent Graph 初始化失败", e);
        }
    }

    public CompiledGraph graph() {
        return graph;
    }

    /**
     * 执行当前最小图，返回输出节点写入的状态。
     */
    public String invoke(AgentCommand command) throws Exception {
        Map<String, Object> input = new HashMap<>();
        input.put(OverAllState.DEFAULT_INPUT_KEY, command.content());
        input.put("conversationId", command.conversationId());
        input.put("userId", command.userId());

        RunnableConfig config = RunnableConfig.builder()
                .threadId(command.conversationId())
                .build();

        Optional<OverAllState> result = graph.invoke(input, config);
        return result.flatMap(state -> state.value(OUTPUT, String.class))
                .orElse("");
    }

    private CompiledGraph buildGraph() throws GraphStateException {
        StateGraph graph = new StateGraph(() -> Map.of(
                OverAllState.DEFAULT_INPUT_KEY, new ReplaceStrategy(),
                OUTPUT, new ReplaceStrategy()
        ));

        graph.addNode("echo", node_async(state -> {
            String input = state.value(OverAllState.DEFAULT_INPUT_KEY, String.class)
                    .orElse("");
            return Map.of(OUTPUT, "Graph Runtime 骨架已接收: " + input);
        }));
        graph.addEdge(START, "echo");
        graph.addEdge("echo", END);

        return graph.compile(CompileConfig.builder()
                .recursionLimit(10)
                .build());
    }
}
