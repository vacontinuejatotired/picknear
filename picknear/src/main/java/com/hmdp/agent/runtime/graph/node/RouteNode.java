package com.hmdp.agent.runtime.graph.node;

import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.action.AsyncEdgeAction;
import com.alibaba.cloud.ai.graph.action.AsyncNodeAction;
import com.hmdp.agent.honesty.DataIntent;
import com.hmdp.agent.honesty.DataIntentClassifier;
import com.hmdp.agent.runtime.graph.state.GraphStateKeys;
import com.hmdp.agent.runtime.graph.state.GraphStopReasons;
import org.springframework.stereotype.Component;

import java.util.Map;

import static com.alibaba.cloud.ai.graph.action.AsyncEdgeAction.edge_async;
import static com.alibaba.cloud.ai.graph.action.AsyncNodeAction.node_async;

/**
 * 根据输入是否需要数据规划选择后续节点。
 */
@Component
public class RouteNode {

    private final DataIntentClassifier dataIntentClassifier;

    public RouteNode(DataIntentClassifier dataIntentClassifier) {
        this.dataIntentClassifier = dataIntentClassifier;
    }

    public String name() {
        return GraphNodeNames.ROUTE;
    }

    public AsyncNodeAction action() {
        return node_async(state -> {
            String input = state.value(OverAllState.DEFAULT_INPUT_KEY, String.class)
                    .orElse("");
            DataIntent intent = dataIntentClassifier.classify(input);
            boolean needsPlanning = intent.isDataQuery();
            return Map.of(
                    GraphStateKeys.NEEDS_PLANNING, needsPlanning,
                    GraphStateKeys.STOP_REASON, needsPlanning
                            ? GraphStopReasons.PLANNING
                            : GraphStopReasons.COMPLETED
            );
        });
    }

    public AsyncEdgeAction edge() {
        return edge_async(state -> state.value(
                GraphStateKeys.NEEDS_PLANNING, Boolean.class).orElse(false)
                ? GraphNodeNames.PLAN
                : GraphNodeNames.RESPOND);
    }
}
