package com.hmdp.agent.runtime.graph.node;

import com.alibaba.cloud.ai.graph.action.AsyncNodeAction;
import com.alibaba.cloud.ai.graph.action.AsyncEdgeAction;
import com.hmdp.agent.runtime.graph.config.GraphRuntimeProperties;
import com.hmdp.agent.runtime.graph.state.GraphStateKeys;
import com.hmdp.agent.runtime.graph.state.GraphStopReasons;
import org.springframework.stereotype.Component;

import java.util.Map;

import static com.alibaba.cloud.ai.graph.action.AsyncNodeAction.node_async;
import static com.alibaba.cloud.ai.graph.action.AsyncEdgeAction.edge_async;

/**
 * 规划预算节点。
 *
 * <p>当前只维护进入规划节点的次数和停止原因；真实计划生成与执行由后续节点承担。</p>
 */
@Component
public class PlanNode {

    private final GraphRuntimeProperties properties;

    public PlanNode(GraphRuntimeProperties properties) {
        this.properties = properties;
    }

    public String name() {
        return GraphNodeNames.PLAN;
    }

    public AsyncNodeAction action() {
        return node_async(state -> {
            boolean needsPlanning = state.value(
                    GraphStateKeys.NEEDS_PLANNING, Boolean.class).orElse(false);
            if (!needsPlanning) {
                return Map.of();
            }
            int current = state.value(
                    GraphStateKeys.PLAN_ITERATIONS, Integer.class).orElse(0);
            if (current >= properties.getMaxPlanIterations()) {
                return Map.of(
                        GraphStateKeys.STOP_REASON,
                        GraphStopReasons.MAX_PLAN_ITERATIONS
                );
            }
            return Map.of(
                    GraphStateKeys.PLAN_ITERATIONS, current + 1,
                    GraphStateKeys.STOP_REASON, GraphStopReasons.PLANNING
            );
        });
    }

    public AsyncEdgeAction edge() {
        return edge_async(state -> GraphStopReasons.MAX_PLAN_ITERATIONS.equals(
                state.value(GraphStateKeys.STOP_REASON, String.class).orElse(""))
                ? GraphNodeNames.FINALIZE
                : GraphNodeNames.EXECUTE);
    }
}
