package com.hmdp.agent.runtime.graph.node;

import com.alibaba.cloud.ai.graph.action.AsyncNodeAction;
import com.hmdp.agent.runtime.graph.state.GraphStateKeys;
import com.hmdp.agent.runtime.graph.state.GraphStopReasons;
import org.springframework.stereotype.Component;

import java.util.Map;

import static com.alibaba.cloud.ai.graph.action.AsyncNodeAction.node_async;

/**
 * 固化本轮停止原因。
 */
@Component
public class FinalizeNode {

    public String name() {
        return GraphNodeNames.FINALIZE;
    }

    public AsyncNodeAction action() {
        return node_async(state -> Map.of(
                GraphStateKeys.STOP_REASON,
                state.value(GraphStateKeys.STOP_REASON, String.class)
                        .orElse(GraphStopReasons.COMPLETED)
        ));
    }
}
