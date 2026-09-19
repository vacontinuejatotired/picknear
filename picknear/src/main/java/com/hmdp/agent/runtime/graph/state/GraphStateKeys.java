package com.hmdp.agent.runtime.graph.state;

/**
 * Graph Runtime 状态键。
 */
public final class GraphStateKeys {

    public static final String OUTPUT = "output";
    public static final String SYSTEM_TEXT = "systemText";
    public static final String HISTORY = "history";
    public static final String USER_ID = "userId";
    public static final String CONVERSATION_ID = "conversationId";
    public static final String MESSAGES = "messages";
    public static final String NEEDS_PLANNING = "needsPlanning";
    public static final String PLAN_ITERATIONS = "planIterations";
    public static final String TOOL_ROUNDS = "toolRounds";
    public static final String TOOL_CALL_COUNT = "toolCallCount";
    public static final String MODEL_CALL_COUNT = "modelCallCount";
    public static final String DEADLINE_EPOCH_MILLIS = "deadlineEpochMillis";
    public static final String PENDING_TOOL_CALLS = "pendingToolCalls";
    public static final String STOP_REASON = "stopReason";

    private GraphStateKeys() {
    }
}
