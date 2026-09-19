package com.hmdp.agent.runtime.graph.state;

/**
 * Graph Runtime 停止原因。
 */
public final class GraphStopReasons {

    public static final String COMPLETED = "COMPLETED";
    public static final String PLANNING = "PLANNING";
    public static final String MAX_PLAN_ITERATIONS = "MAX_PLAN_ITERATIONS";

    private GraphStopReasons() {
    }
}
