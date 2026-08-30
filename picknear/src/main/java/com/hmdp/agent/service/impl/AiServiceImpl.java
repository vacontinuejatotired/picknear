package com.hmdp.agent.service.impl;

import com.hmdp.agent.config.ChatModelObservationConventionConfig;
import com.hmdp.agent.observability.model.CallerType;
import com.hmdp.agent.context.AgentContext;
import com.hmdp.agent.history.HistoryRecorder;
import com.hmdp.agent.hook.PromptHookExecutor;
import com.hmdp.agent.observability.api.AgentSpan;
import com.hmdp.agent.observability.api.AgentTracer;
import com.hmdp.agent.prompt.PromptKeys;
import com.hmdp.agent.prompt.PromptService;
import com.hmdp.agent.service.AiService;
import com.hmdp.agent.stream.SseEventConstants;
import com.hmdp.agent.stream.SseResponseProcessor;
import com.hmdp.agent.stream.SseUtils;
import com.hmdp.agent.stream.StreamingChatInvoker;
import com.hmdp.agent.util.TextUtils;
import com.hmdp.utils.UserHolder;

import io.micrometer.observation.Observation;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

@Service
@Slf4j
public class AiServiceImpl implements AiService {

    @Resource
    @Qualifier("aliibabaChatClient")
    private ChatClient chatClient;

    @Resource
    private PromptHookExecutor promptHookExecutor;

    @Resource(name = "aiTaskExecutor")
    private Executor aiTaskExecutor;

    @Resource
    private AgentTracer agentTracer;

    @Resource
    private StreamingChatInvoker streamingChatInvoker;

    @Resource
    private SseResponseProcessor sseResponseProcessor;

    @Resource
    private HistoryRecorder historyRecorder;

    @Resource
    private PromptService promptService;

    /** 系统提示词变量：当前用户 ID（可空，渲染器对缺变量保留字面量） */
    private Map<String, String> systemVars(Long userId) {
        return Map.of("userId", userId != null ? String.valueOf(userId) : "");
    }

    @Override
    public String chatReturnStringResult(String content, String conversationId) {
        log.info("AI 调用：{}", content);

        Long userId = UserHolder.getUserId();

        // 1-3. Hook 链执行 + 决策（双模共用 PromptHookExecutor）
        PromptHookExecutor.HookOutcome outcome = promptHookExecutor.execute(content, conversationId, userId, null);
        if (outcome.blocked()) {
            return "❌ " + outcome.blockReason();
        }

        // 4. 正常调用 LLM（系统提示词每次请求经 PromptService 注入，支持按用户个性化）
        ChatModelObservationConventionConfig.mark(CallerType.PHASE1);
        String result;
        try {
            result = chatClient.prompt()
                    .system(promptService.render(PromptKeys.SYSTEM_MAIN, systemVars(userId)))
                    .user(outcome.finalContent())
                    .call().content();
        } finally {
            ChatModelObservationConventionConfig.clear();
        }
        log.info("AI 回复：{}", result);

        // 历史会话：JSON 模式成功回合落库（BLOCK 已提前 return，不落库）
        historyRecorder.recordBestEffort(userId, conversationId, content, result);
        return result;
    }

    @Override
    public void chatWithToolcall(String content, String conversationId, SseEmitter emitter, AgentSpan rootSpan) {
        log.info("AI SSE 工具调用, content={}", content);
        try {
            doChatWithToolcall(content, conversationId, emitter, rootSpan);
        } catch (Exception e) {
            // 响应已提交为 SSE：异常不能抛回 WebExceptionAdvice（会污染已提交的流），
            // 必须转为 SSE error 事件，否则前端收不到任何提示
            log.error("AI SSE 会话初始化异常, content={}", content, e);
            SseUtils.safeSend(emitter, SseUtils.errorEvent("抱歉，AI 服务暂时不可用（" + TextUtils.errorSummary(e) + "），请稍后再试。"));
            emitter.complete();
        }
    }

    /**
     * 实际执行 SSE 流式逻辑：同步段（会话/Hook/决策）+ 异步段（逐 token 推送）。
     * <p>
     * 同步段任一环（会话 ID 同步、Hook 链、决策处理）抛异常时，
     * 会向上传播到 {@link #chatWithToolcall} 统一转为 SSE 错误事件。
     * </p>
     */
    private void doChatWithToolcall(String content, String conversationId, SseEmitter emitter, AgentSpan rootSpan) {
        Long userId = UserHolder.getUserId();
        try {
        // 1-3. Hook 链执行 + 决策（双模共用 PromptHookExecutor；rootSpan 供跨线程挂载）
        PromptHookExecutor.HookOutcome outcome = promptHookExecutor.execute(content, conversationId, userId, rootSpan);
        if (outcome.blocked()) {
            SseUtils.safeSend(emitter, SseUtils.errorEvent(outcome.blockReason()));
            emitter.complete();
            return;
        }
        AgentContext ctx = outcome.ctx();
        String finalContent = outcome.finalContent();
        // 4. 流式调用 AI（真正的逐 token 推送，异步线程）
        //    观测：先 resume 根 span（跨线程传播，架构文档 §6.2），后续 span 自动挂树
        AgentSpan phase1Root = rootSpan;
        CompletableFuture.runAsync(() -> {
            // rootSpan 为 null（旧调用方/快照恢复）时跳过 resume，埋点整体 Fail-Open
            try (Observation.Scope rootScope = phase1Root != null
                    ? agentTracer.resume(phase1Root) : Observation.Scope.NOOP) {
                // 系统提示词渲染一次（缓存命中后开销≈0），重试循环不重复渲染
                String systemText = promptService.render(PromptKeys.SYSTEM_MAIN, systemVars(userId));

                // 流式调用 + 重试（StreamingChatInvoker：ChatModel 直调 + 逐 token 推送 + phase1 观测）
                StreamingChatInvoker.StreamOutcome streamOutcome =
                        streamingChatInvoker.streamWithRetry(systemText, finalContent, emitter);
                if (streamOutcome.failed()) {
                    // 所有重试耗尽，给用户友好提示而非原始异常（完整堆栈已在上方 warn 日志记录）
                    String friendlyMsg = "抱歉，AI 服务暂时不可用（" + TextUtils.errorSummary(streamOutcome.lastError()) + "），请稍后再试。";
                    SseUtils.safeSend(emitter, SseUtils.progressEvent(SseEventConstants.STAGE_MERGING, SseEventConstants.TEXT_MERGING_DONE));
                    SseUtils.safeSend(emitter, SseUtils.errorEvent(friendlyMsg));
                    emitter.complete();
                    return;
                }
                String fullResponse = streamOutcome.fullResponse();

                // 后处理：AfterAiHook → 决策观测 → 响应路由 → 历史落库（SseResponseProcessor）
                sseResponseProcessor.process(ctx, content, finalContent, fullResponse, emitter);
            }
        }, aiTaskExecutor);
        } finally {
            // 断链修复（2026-08-04，R1）：请求线程的根 scope 在此关闭（正常/异常路径都清理），
            // 防 Tomcat 线程复用导致的跨会话 trace 污染；异步段靠 resume(rootSpan) 重新挂载，
            // 不依赖原 scope（AgentSpanImpl.closeRootScope 仅属主线程实际 close，幂等）。
            if (rootSpan != null) {
                rootSpan.closeRootScope();
            }
        }
    }

}
