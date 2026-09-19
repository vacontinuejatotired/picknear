package com.hmdp.agent.runtime.graph.node;

import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.action.AsyncEdgeAction;
import com.alibaba.cloud.ai.graph.action.AsyncNodeAction;
import com.hmdp.agent.runtime.graph.config.GraphRuntimeProperties;
import com.hmdp.agent.runtime.graph.state.GraphStateKeys;
import com.hmdp.agent.runtime.graph.state.GraphStopReasons;
import org.springframework.stereotype.Component;

import java.util.Map;

import static com.alibaba.cloud.ai.graph.action.AsyncEdgeAction.edge_async;
import static com.alibaba.cloud.ai.graph.action.AsyncNodeAction.node_async;

/**
 * 校验工具循环是否还能继续，并在预算耗尽时收口。
 */
@Component
public class VerifyNode {

    private final GraphRuntimeProperties properties;

    public VerifyNode(GraphRuntimeProperties properties) {
        this.properties = properties;
    }

    public String name() {
        return GraphNodeNames.VERIFY;
    }

    public AsyncNodeAction action() {
        return node_async(state -> {
            boolean pending = state.value(
                    GraphStateKeys.PENDING_TOOL_CALLS, Boolean.class).orElse(false);
            if (!pending) {
                return Map.of(
                        GraphStateKeys.STOP_REASON,
                        state.value(GraphStateKeys.STOP_REASON, String.class)
                                .orElse(GraphStopReasons.COMPLETED)
                );
            }

            int toolRounds = intValue(state, GraphStateKeys.TOOL_ROUNDS);
            if (toolRounds >= properties.getMaxToolRounds()) {
                return budgetStop(GraphStopReasons.MAX_TOOL_ROUNDS,
                        "工具轮次已达到本轮上限，暂时无法继续处理。");
            }

            int toolCalls = intValue(state, GraphStateKeys.TOOL_CALL_COUNT);
            if (toolCalls >= properties.getMaxTotalToolCalls()) {
                return budgetStop(GraphStopReasons.MAX_TOTAL_TOOL_CALLS,
                        "工具调用已达到本轮上限，暂时无法继续处理。");
            }

            int modelCalls = intValue(state, GraphStateKeys.MODEL_CALL_COUNT);
            if (modelCalls >= properties.getMaxModelCalls()) {
                return budgetStop(GraphStopReasons.MAX_MODEL_CALLS,
                        "模型调用已达到本轮上限，暂时无法继续处理。");
            }

            long deadline = state.value(
                    GraphStateKeys.DEADLINE_EPOCH_MILLIS, Long.class).orElse(Long.MAX_VALUE);
            if (System.currentTimeMillis() >= deadline) {
                return budgetStop(GraphStopReasons.TIMEOUT,
                        "本轮处理已超时，暂时无法继续处理。");
            }

            return Map.of(
                    GraphStateKeys.STOP_REASON, GraphStopReasons.TOOL_CONTINUE
            );
        });
    }

    public AsyncEdgeAction edge() {
        return edge_async(state -> state.value(
                GraphStateKeys.PENDING_TOOL_CALLS, Boolean.class).orElse(false)
                ? GraphNodeNames.EXECUTE
                : GraphNodeNames.FINALIZE);
    }

    private static Map<String, Object> budgetStop(String reason, String message) {
        return Map.of(
                GraphStateKeys.OUTPUT, GraphNodeOutputs.text(message),
                GraphStateKeys.PENDING_TOOL_CALLS, false,
                GraphStateKeys.STOP_REASON, reason
        );
    }

    private static int intValue(OverAllState state, String key) {
        return state.value(key, Integer.class).orElse(0);
    }
}
