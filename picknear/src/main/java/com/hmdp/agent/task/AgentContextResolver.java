package com.hmdp.agent.task;

import com.hmdp.agent.context.AgentContext;
import com.hmdp.agent.context.AgentContextHolder;
import com.hmdp.agent.observability.api.AgentSpan;

/**
 * 异步段上下文解析工具（从 TaskPlanner 拆出，静态无状态）。
 * <p>
 * 解析优先级统一为：AgentContextHolder（请求入口创建、异步边界 Propagator 传播）
 * → 参数 ctx（直调/测试路径兜底）→ 调用方 fallback（审批记录/快照等持久化兜底）。
 * </p>
 */
public final class AgentContextResolver {

    private AgentContextResolver() {
    }

    /**
     * 解析用户 ID：优先 AgentContextHolder，未设置时回退参数 ctx，再兜底 fallback。
     */
    public static Long resolveUserId(AgentContext ctx, Long fallback) {
        AgentContext agentCtx = AgentContextHolder.get();
        if (agentCtx != null && agentCtx.userId() != null) {
            return agentCtx.userId();
        }
        if (ctx != null && ctx.userId() != null) {
            return ctx.userId();
        }
        return fallback;
    }

    /**
     * 解析会话 ID：优先级同 {@link #resolveUserId}。
     */
    public static String resolveConversationId(AgentContext ctx, String fallback) {
        AgentContext agentCtx = AgentContextHolder.get();
        if (agentCtx != null && agentCtx.conversationId() != null) {
            return agentCtx.conversationId();
        }
        if (ctx != null && ctx.conversationId() != null) {
            return ctx.conversationId();
        }
        return fallback;
    }

    /**
     * 解析原始输入：优先级同 {@link #resolveUserId}（快照恢复时 ctx 无原文，取 fallback）。
     */
    public static String resolveOriginalContent(AgentContext ctx, String fallback) {
        AgentContext agentCtx = AgentContextHolder.get();
        if (agentCtx != null && agentCtx.originalInput() != null) {
            return agentCtx.originalInput();
        }
        if (ctx != null && ctx.originalInput() != null) {
            return ctx.originalInput();
        }
        return fallback;
    }

    /**
     * 解析根 span：优先级同 {@link #resolveUserId}（explicitRootSpan 由调用方决定兜底顺序）。
     */
    public static AgentSpan resolveRootSpan(AgentContext ctx, AgentSpan fallback) {
        AgentContext agentCtx = AgentContextHolder.get();
        if (agentCtx != null && agentCtx.rootSpan() != null) {
            return agentCtx.rootSpan();
        }
        if (ctx != null && ctx.rootSpan() != null) {
            return ctx.rootSpan();
        }
        return fallback;
    }
}
