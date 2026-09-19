package com.hmdp.agent.runtime.graph.state;

/**
 * Graph Runtime 停止原因。
 */
public final class GraphStopReasons {

    public static final String COMPLETED = "COMPLETED";
    public static final String PLANNING = "PLANNING";
    public static final String TOOL_CONTINUE = "TOOL_CONTINUE";
    public static final String MAX_PLAN_ITERATIONS = "MAX_PLAN_ITERATIONS";
    public static final String MAX_TOOL_ROUNDS = "MAX_TOOL_ROUNDS";
    public static final String MAX_TOTAL_TOOL_CALLS = "MAX_TOTAL_TOOL_CALLS";
    public static final String MAX_MODEL_CALLS = "MAX_MODEL_CALLS";
    public static final String TIMEOUT = "TIMEOUT";

    private GraphStopReasons() {
    }
}
