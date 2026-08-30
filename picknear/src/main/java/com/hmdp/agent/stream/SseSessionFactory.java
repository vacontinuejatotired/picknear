package com.hmdp.agent.stream;

import com.hmdp.agent.observability.api.AgentSpan;
import com.hmdp.agent.observability.api.AgentTracer;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;

/**
 * SSE 会话装配工厂（从 ChatController 拆出）。
 * <p>
 * 统一三件事：会话根 span 创建、ObservedSseEmitter 构造（超时/TTL 常量收敛）、
 * conversationId 元事件推送。chat() 与 confirm() 两处 SSE 分支此前各手写一份，
 * 根因是同一套「root 必须非 null 才能建 emitter → 三回调只留日志 → 先推 meta」的
 * 断链修复约定（2026-08-04）散落重复。
 * </p>
 * <p>
 * 生命周期约定：ObservedSseEmitter 将 SSE 生命周期与会话根 span 绑定，
 * complete / completeWithError / 容器超时 / 容器错误 / 兜底 TTL 任一路径都收敛结束根 span，
 * 回调注册与业务编排仍由调用方负责。
 * </p>
 */
@Slf4j
@Component
public class SseSessionFactory {

    /** SSE 超时时间：30 分钟（AI 长思考场景） */
    private static final long SSE_TIMEOUT = 30 * 60 * 1000L;

    /** ObservedSseEmitter 兜底 TTL：容器超时(30min)先触发 onTimeout→finish；此值为最后防线，须 > SSE_TIMEOUT */
    private static final long SSE_GUARD_TIMEOUT = 32 * 60 * 1000L;

    @Resource
    private AgentTracer agentTracer;

    @Resource
    private TaskScheduler taskScheduler;

    /**
     * 打开 SSE 会话：创建会话根 span（agent.session）+ 绑定生命周期的 ObservedSseEmitter。
     * <p>
     * 顺序契约：先创建 root 再构造 emitter（root 必须非 null，ObservedSseEmitter 依赖它收敛）。
     * </p>
     */
    public ChatSseSession open(String conversationId, Long userId) {
        AgentSpan root = agentTracer.startSession(conversationId, String.valueOf(userId));
        SseEmitter emitter = new ObservedSseEmitter(SSE_TIMEOUT, root, taskScheduler, SSE_GUARD_TIMEOUT);
        return new ChatSseSession(root, emitter);
    }

    /**
     * 推送 conversationId 元事件（JSON 格式，前端据此识别为元事件，不混入回答文本）。
     *
     * @throws IOException 推送失败（调用方转 completeWithError）
     */
    public void sendConversationId(SseEmitter emitter, String conversationId) throws IOException {
        emitter.send(SseEmitter.event()
                .data("{\"type\":\"meta\",\"conversationId\":\"" + conversationId + "\"}"));
    }

    /**
     * SSE 会话句柄：根 span + emitter（同一生命周期，emitter 负责收敛根 span）。
     */
    public record ChatSseSession(AgentSpan root, SseEmitter emitter) {
    }
}
