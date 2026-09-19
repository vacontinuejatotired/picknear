package com.hmdp.agent.runtime.graph.definition;

import com.alibaba.cloud.ai.graph.CompileConfig;
import com.alibaba.cloud.ai.graph.CompiledGraph;
import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.alibaba.cloud.ai.graph.StateGraph;
import com.alibaba.cloud.ai.graph.exception.GraphStateException;
import com.alibaba.cloud.ai.graph.state.strategy.ReplaceStrategy;
import com.hmdp.agent.access.AgentCommand;
import com.hmdp.agent.runtime.graph.config.GraphRuntimeProperties;
import com.hmdp.agent.runtime.graph.node.ExecuteNode;
import com.hmdp.agent.runtime.graph.node.FinalizeNode;
import com.hmdp.agent.runtime.graph.node.GraphNodeNames;
import com.hmdp.agent.runtime.graph.node.PlanNode;
import com.hmdp.agent.runtime.graph.node.RespondNode;
import com.hmdp.agent.runtime.graph.node.RouteNode;
import com.hmdp.agent.runtime.graph.node.VerifyNode;
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

    private final GraphRuntimeProperties properties;
    private final CompiledGraph graph;

    public AgentGraphFactory(RespondNode respondNode,
                             RouteNode routeNode,
                             PlanNode planNode,
                             ExecuteNode executeNode,
                             VerifyNode verifyNode,
                             FinalizeNode finalizeNode,
                             GraphRuntimeProperties properties) {
        this.properties = properties;
        try {
            this.graph = buildGraph(
                    respondNode,
                    routeNode,
                    planNode,
                    executeNode,
                    verifyNode,
                    finalizeNode
            );
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
        input.put(GraphStateKeys.USER_ID, command.userId());
        input.put(GraphStateKeys.CONVERSATION_ID, command.conversationId());
        input.put(GraphStateKeys.MESSAGES, List.of());
        input.put(GraphStateKeys.PLAN_ITERATIONS, 0);
        input.put(GraphStateKeys.TOOL_ROUNDS, 0);
        input.put(GraphStateKeys.TOOL_CALL_COUNT, 0);
        input.put(GraphStateKeys.MODEL_CALL_COUNT, 0);
        input.put(GraphStateKeys.PENDING_TOOL_CALLS, false);
        input.put(GraphStateKeys.DEADLINE_EPOCH_MILLIS,
                System.currentTimeMillis() + properties.getTotalTimeoutSeconds() * 1000L);

        RunnableConfig config = RunnableConfig.builder()
                .threadId(command.conversationId())
                .build();

        return graph.stream(input, config);
    }

    private CompiledGraph buildGraph(RespondNode respondNode,
                                     RouteNode routeNode,
                                     PlanNode planNode,
                                     ExecuteNode executeNode,
                                     VerifyNode verifyNode,
                                     FinalizeNode finalizeNode)
            throws GraphStateException {
        StateGraph graph = new StateGraph(() -> Map.ofEntries(
                Map.entry(OverAllState.DEFAULT_INPUT_KEY, new ReplaceStrategy()),
                Map.entry(GraphStateKeys.SYSTEM_TEXT, new ReplaceStrategy()),
                Map.entry(GraphStateKeys.HISTORY, new ReplaceStrategy()),
                Map.entry(GraphStateKeys.USER_ID, new ReplaceStrategy()),
                Map.entry(GraphStateKeys.CONVERSATION_ID, new ReplaceStrategy()),
                Map.entry(GraphStateKeys.MESSAGES, new ReplaceStrategy()),
                Map.entry(OUTPUT, new ReplaceStrategy()),
                Map.entry(GraphStateKeys.NEEDS_PLANNING, new ReplaceStrategy()),
                Map.entry(GraphStateKeys.PLAN_ITERATIONS, new ReplaceStrategy()),
                Map.entry(GraphStateKeys.TOOL_ROUNDS, new ReplaceStrategy()),
                Map.entry(GraphStateKeys.TOOL_CALL_COUNT, new ReplaceStrategy()),
                Map.entry(GraphStateKeys.MODEL_CALL_COUNT, new ReplaceStrategy()),
                Map.entry(GraphStateKeys.DEADLINE_EPOCH_MILLIS, new ReplaceStrategy()),
                Map.entry(GraphStateKeys.PENDING_TOOL_CALLS, new ReplaceStrategy()),
                Map.entry(GraphStateKeys.STOP_REASON, new ReplaceStrategy())
        ));

        graph.addNode(respondNode.name(), respondNode.action());
        graph.addNode(routeNode.name(), routeNode.action());
        graph.addNode(planNode.name(), planNode.action());
        graph.addNode(executeNode.name(), executeNode.action());
        graph.addNode(verifyNode.name(), verifyNode.action());
        graph.addNode(finalizeNode.name(), finalizeNode.action());

        graph.addEdge(START, GraphNodeNames.ROUTE);
        graph.addConditionalEdges(
                GraphNodeNames.ROUTE,
                routeNode.edge(),
                Map.of(
                        GraphNodeNames.PLAN, GraphNodeNames.PLAN,
                        GraphNodeNames.RESPOND, GraphNodeNames.RESPOND
                )
        );
        graph.addEdge(GraphNodeNames.RESPOND, GraphNodeNames.FINALIZE);
        graph.addConditionalEdges(
                GraphNodeNames.PLAN,
                planNode.edge(),
                Map.of(
                        GraphNodeNames.EXECUTE, GraphNodeNames.EXECUTE,
                        GraphNodeNames.FINALIZE, GraphNodeNames.FINALIZE
                )
        );
        graph.addEdge(GraphNodeNames.EXECUTE, GraphNodeNames.VERIFY);
        graph.addConditionalEdges(
                GraphNodeNames.VERIFY,
                verifyNode.edge(),
                Map.of(
                        GraphNodeNames.EXECUTE, GraphNodeNames.EXECUTE,
                        GraphNodeNames.FINALIZE, GraphNodeNames.FINALIZE
                )
        );
        graph.addEdge(GraphNodeNames.FINALIZE, END);

        return graph.compile(CompileConfig.builder()
                .recursionLimit(properties.getMaxPlanIterations()
                        + properties.getMaxToolRounds() * 2 + 8)
                .build());
    }

}
