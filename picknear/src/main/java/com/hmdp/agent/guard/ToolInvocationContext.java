package com.hmdp.agent.guard;
        
/**
 * 工具调用上下文 — 携带本次工具调用的全部可观测信息
 * <p>
 * 由 {@link GuardedToolCallback} 在 {@code call()} 执行前构造，
 * 传递给 {@link ToolGuardManager#evaluate(ToolInvocationContext)}，
 * 所有 {@link ToolGuardPolicy} 根据此上下文投票。
 * </p>
 * <p>
 * 注意：此类仅承载数据<strong>不包含任何业务 Service 依赖</strong>，
 * 符合策略层的无状态原则。
 * </p>
 */
public class ToolInvocationContext {

    /** 工具名称（如 "deleteBlog"、"publishTestBlog"） */
    private final String toolName;

    /** 工具参数的 JSON 字符串 */
    private final String arguments;

    /** 当前会话 ID（用于频率限制等） */
    private final String conversationId;

    /** 当前登录用户 ID（可选，部分策略可能参考） */
    private final Long userId;

    /** 本轮对话中第几次工具调用 */
    private final int invocationCount;

    private ToolInvocationContext(Builder builder) {
        this.toolName = builder.toolName;
        this.arguments = builder.arguments;
        this.conversationId = builder.conversationId;
        this.userId = builder.userId;
        this.invocationCount = builder.invocationCount;
    }

    // ---- getters ----

    public String getToolName() { return toolName; }
    public String getArguments() { return arguments; }
    public String getConversationId() { return conversationId; }
    public Long getUserId() { return userId; }
    public int getInvocationCount() { return invocationCount; }

    // ---- builder ----

    public static Builder builder() { return new Builder(); }

    public static class Builder {
        private String toolName;
        private String arguments;
        private String conversationId;
        private Long userId;
        private int invocationCount;

        public Builder toolName(String toolName) { this.toolName = toolName; return this; }
        public Builder arguments(String arguments) { this.arguments = arguments; return this; }
        public Builder conversationId(String conversationId) { this.conversationId = conversationId; return this; }
        public Builder userId(Long userId) { this.userId = userId; return this; }
        public Builder invocationCount(int invocationCount) { this.invocationCount = invocationCount; return this; }
        public ToolInvocationContext build() { return new ToolInvocationContext(this); }
    }
}
