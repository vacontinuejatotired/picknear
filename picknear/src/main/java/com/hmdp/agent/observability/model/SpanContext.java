package com.hmdp.agent.observability.model;

import com.hmdp.agent.observability.api.AgentSpan;

/**
 * 跨线程传播载体（M2 核心设计，对齐架构文档 §6.2）。
 * <p>
 * 链路跨两个线程池（aiTaskExecutor / subtaskExecutor），ThreadLocal 天然丢失。
 * 会话根 span 由 Controller 主线程创建后，通过本载体显式传递到异步线程；
 * 异步线程入口调用 {@code AgentTracer.resume(ctx)} 重新 openScope 根 span，
 * 之后 {@code start()} 创建的子 span 即自动挂到根下（Micrometer 在创建时固化父级）。
 * </p>
 */
public class SpanContext {

    /** 会话根 span（未序列化，仅内存传递） */
    private final AgentSpan rootSpan;

    private final String conversationId;

    private final String userId;

    public SpanContext(AgentSpan rootSpan, String conversationId, String userId) {
        this.rootSpan = rootSpan;
        this.conversationId = conversationId;
        this.userId = userId;
    }

    public AgentSpan rootSpan() {
        return rootSpan;
    }

    public String conversationId() {
        return conversationId;
    }

    public String userId() {
        return userId;
    }
}
