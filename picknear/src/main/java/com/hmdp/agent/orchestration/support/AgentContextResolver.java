package com.hmdp.agent.orchestration.support;

import com.hmdp.agent.context.AgentContext;
import com.hmdp.agent.context.AgentContextHolder;
import com.hmdp.agent.observability.api.AgentSpan;

/**
 * 异步段上下文解析工具（静态无状态）。
 * <p>
 * 解析优先级统一为：AgentContextHolder → 参数 ctx → 调用方 fallback。
 * </p>
 */
public final class AgentContextResolver {

    private AgentContextResolver() {
    }

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
