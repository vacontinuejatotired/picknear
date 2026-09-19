package com.hmdp.agent.runtime.graph.definition;

import com.alibaba.cloud.ai.graph.CompileConfig;
import com.alibaba.cloud.ai.graph.CompiledGraph;
import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.alibaba.cloud.ai.graph.StateGraph;
import com.alibaba.cloud.ai.graph.exception.GraphStateException;
import com.alibaba.cloud.ai.graph.state.strategy.ReplaceStrategy;
import com.hmdp.agent.access.AgentCommand;
import com.hmdp.agent.runtime.graph.node.FinalizeNode;
import com.hmdp.agent.runtime.graph.node.GraphNodeNames;
import com.hmdp.agent.runtime.graph.node.PlanNode;
import com.hmdp.agent.runtime.graph.node.RespondNode;
import com.hmdp.agent.runtime.graph.node.RouteNode;
import com.hmdp.agent.runtime.graph.state.GraphStateKeys;
import org.springframework.ai.chat.messages.Message;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static com.alibaba.cloud.ai.graph.StateGraph.END;
import static com.alibaba.cloud.ai.graph.StateGraph.START;

/**
 * Alibaba Graph 装配入口。
 */
@Component
public class AgentGraphFactory {

    public static final String OUTPUT = GraphStateKeys.OUTPUT;

    private final CompiledGraph graph;

    public AgentGraphFactory(RespondNode respondNode,
                             RouteNode routeNode,
                             PlanNode planNode,
                             FinalizeNode finalizeNode) {
        try {
            this.graph = buildGraph(respondNode, routeNode, planNode, finalizeNode);
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
    public Flux<com.alibaba.cloud.ai.graph.NodeOutput> stream(
            AgentCommand command,
            String systemText,
            List<Message> history) {
        Map<String, Object> input = new HashMap<>();
        input.put(OverAllState.DEFAULT_INPUT_KEY, command.content());
        input.put(GraphStateKeys.SYSTEM_TEXT, systemText);
        input.put(GraphStateKeys.HISTORY, history);

        RunnableConfig config = RunnableConfig.builder()
                .threadId(command.conversationId())
                .build();

        return graph.stream(input, config);
    }

    private CompiledGraph buildGraph(RespondNode respondNode,
                                     RouteNode routeNode,
                                     PlanNode planNode,
                                     FinalizeNode finalizeNode)
            throws GraphStateException {
        StateGraph graph = new StateGraph(() -> Map.of(
                OverAllState.DEFAULT_INPUT_KEY, new ReplaceStrategy(),
                GraphStateKeys.SYSTEM_TEXT, new ReplaceStrategy(),
                GraphStateKeys.HISTORY, new ReplaceStrategy(),
                OUTPUT, new ReplaceStrategy(),
                GraphStateKeys.NEEDS_PLANNING, new ReplaceStrategy(),
                GraphStateKeys.PLAN_ITERATIONS, new ReplaceStrategy(),
                GraphStateKeys.STOP_REASON, new ReplaceStrategy()
        ));

        graph.addNode(respondNode.name(), respondNode.action());
        graph.addNode(routeNode.name(), routeNode.action());
        graph.addNode(planNode.name(), planNode.action());
        graph.addNode(finalizeNode.name(), finalizeNode.action());

        graph.addEdge(START, GraphNodeNames.RESPOND);
        graph.addEdge(GraphNodeNames.RESPOND, GraphNodeNames.ROUTE);
        graph.addConditionalEdges(
                GraphNodeNames.ROUTE,
                routeNode.edge(),
                Map.of(
                        GraphNodeNames.PLAN, GraphNodeNames.PLAN,
                        GraphNodeNames.FINALIZE, GraphNodeNames.FINALIZE
                )
        );
        graph.addEdge(GraphNodeNames.PLAN, GraphNodeNames.FINALIZE);
        graph.addEdge(GraphNodeNames.FINALIZE, END);

        return graph.compile(CompileConfig.builder()
                .recursionLimit(10)
                .build());
    }

}
