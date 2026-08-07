package com.hmdp.agent.service.impl;

import com.hmdp.agent.observability.api.AgentSpan;
import com.hmdp.agent.observability.api.AgentTracer;
import com.hmdp.agent.observability.model.AgentSpanSpec;
import com.hmdp.agent.prompt.PromptKeys;
import com.hmdp.agent.prompt.PromptService;
import com.hmdp.agent.response.AiResponseRouter;
import com.hmdp.agent.service.AiService;
import com.hmdp.agent.tool.ToolBeanCollector;
import com.hmdp.agent.util.SseEventConstants;
import com.hmdp.agent.util.SseUtils;
import com.hmdp.agent.hook.AfterAiHookChain;
import com.hmdp.agent.hook.ChatContext;
import com.hmdp.agent.hook.HookResult;
import com.hmdp.agent.hook.PromptHookChain;
import com.hmdp.agent.service.AgentHistoryService;
import com.hmdp.utils.UserHolder;

import io.micrometer.observation.Observation;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.ChatClient.ChatClientRequestSpec;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.SystemMessage;
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
    private PromptHookChain promptHookChain;

    @Resource
    private ChatMemory chatMemory;

    @Resource
    private ToolBeanCollector toolBeanCollector;

    @Resource
    private AfterAiHookChain afterAiHookChain;

    @Resource
    private AiResponseRouter responseRouter;

    @Resource(name = "aiTaskExecutor")
    private Executor aiTaskExecutor;

    @Resource
    private AgentTracer agentTracer;

    @Resource
    private io.micrometer.observation.ObservationRegistry observationRegistry;

    @Resource
    private org.springframework.ai.chat.model.ChatModel chatModel;

    @Resource
    private AgentHistoryService historyService;

    @Resource
    private PromptService promptService;

    /** 系统提示词变量：当前用户 ID（可空，渲染器对缺变量保留字面量） */
    private Map<String, String> systemVars(Long userId) {
        return Map.of("userId", userId != null ? String.valueOf(userId) : "");
    }

    @Override
    public String chatReturnStringResult(String content, String conversationId) {
        log.info("AI 调用：{}", content);

        // 0. 将会话 ID 同步到工具收集器（供 GuardedToolCallback / RateLimitPolicy 使用）
        toolBeanCollector.setConversationId(conversationId);

        Long userId = UserHolder.getUserId();

        // 1. 构造 Hook 上下文
        ChatContext ctx = ChatContext.builder()
                .userId(userId)
                .conversationId(conversationId)
                .originalContent(content)
                .history(chatMemory.get(conversationId))
                .build();

        // 2. 执行 Hook 链
        HookResult hookResult = promptHookChain.execute(content, ctx);

        // 3. 处理决策
        String finalContent = processHookResult(hookResult, content, conversationId);
        if (finalContent == null) {
            // BLOCK 时返回错误信息
            return "❌ " + hookResult.getReason();
        }

        // 4. 正常调用 LLM（系统提示词每次请求经 PromptService 注入，支持按用户个性化）
        String result = chatClient.prompt()
                .system(promptService.render(PromptKeys.SYSTEM_MAIN, systemVars(userId)))
                .user(finalContent)
                .call().content();
        log.info("AI 回复：{}", result);

        // 历史会话：JSON 模式成功回合落库（BLOCK 已提前 return，不落库）
        recordTurnBestEffort(userId, conversationId, content, result);
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
            SseUtils.safeSend(emitter, SseUtils.errorEvent("抱歉，AI 服务暂时不可用（" + errorSummary(e) + "），请稍后再试。"));
            emitter.complete();
        }
    }

    /**
     * 实际执行 SSE 流式逻辑：同步段（会话/Hook/决策）+ 异步段（逐 token 推送）。
     * <p>
     * 同步段任一环（会话 ID 同步、{@code chatMemory.get}、Hook 链、决策处理）抛异常时，
     * 会向上传播到 {@link #chatWithToolcall} 统一转为 SSE 错误事件。
     * </p>
     */
    private void doChatWithToolcall(String content, String conversationId, SseEmitter emitter, AgentSpan rootSpan) {
        Long userId = UserHolder.getUserId();
        try {
        // 0. 将会话 ID 同步到工具收集器
        toolBeanCollector.setConversationId(conversationId);

        // 1. 构造 Hook 上下文（在主线程执行，UserHolder 有效）
        ChatContext ctx = ChatContext.builder()
                .userId(userId)
                .conversationId(conversationId)
                .originalContent(content)
                .history(chatMemory.get(conversationId))
                .build();
        ctx.setRootSpan(rootSpan);

        // 2. 执行 Hook 链（观测：agent.prompt_hook，链执行后结束）
        HookResult hookResult;
        try (AgentSpan hookSpan = agentTracer.start(AgentSpanSpec.PROMPT_HOOK, null)) {
            hookResult = promptHookChain.execute(content, ctx);
            hookSpan.attribute("hook.decision", String.valueOf(hookResult.getDecision()));
            if (hookResult.getHookName() != null) {
                hookSpan.attribute("hook.name", hookResult.getHookName());
            }
        }

        // 3. 处理决策（仍在主线程）
        String finalContent = processHookResult(hookResult, content, conversationId);
        if (finalContent == null) {
            SseUtils.safeSend(emitter, SseUtils.errorEvent(hookResult.getReason()));
            emitter.complete();
            return;
        }

        // 4. 流式调用 AI（真正的逐 token 推送，异步线程）
        //    观测：先 resume 根 span（跨线程传播，架构文档 §6.2），后续 span 自动挂树
        AgentSpan phase1Root = rootSpan;
        CompletableFuture.runAsync(() -> {
            // rootSpan 为 null（旧调用方/快照恢复）时跳过 resume，埋点整体 Fail-Open
            try (Observation.Scope rootScope = phase1Root != null
                    ? agentTracer.resume(phase1Root) : Observation.Scope.NOOP) {
                int maxAttempts = 3;
                Exception lastError = null;
                String currentContent = finalContent;
                // 系统提示词渲染一次（缓存命中后开销≈0），重试循环不重复渲染
                String systemText = promptService.render(PromptKeys.SYSTEM_MAIN, systemVars(userId));

                // 观测：Phase1 整段（含重试循环），属性 attempt 标记成功轮次
                try (AgentSpan phase1 = agentTracer.start(AgentSpanSpec.PHASE1, null)) {
                    for (int attempt = 1; attempt <= maxAttempts; attempt++) {
                        try {
                            phase1.attribute("attempt", String.valueOf(attempt));
                            // 流式断链修复（2026-08-03）：绕开 ChatClient 层，直接调 ChatModel 层流式。
                            // 原因（字节码+实测三层验证）：ChatClient.stream() 内部有 ChatClient 层观察对象
                            // （spring.ai.chat.client），被 handler 链上 meter 优先的 composite 匹配、
                            // 不产生 tracing span；model 层观察对象从 Reactor context 读到它作为父，
                            // TracingContext 缺失 → 断链（新 traceId）。绕开后 context 里只有下面
                            // 写入的当前栈顶 observation（phase1），model 层观察对象直接挂上。
                            Observation streamParent = observationRegistry.getCurrentObservation();
                            org.springframework.ai.chat.prompt.Prompt streamPrompt =
                                    new org.springframework.ai.chat.prompt.Prompt(
                                            java.util.List.of(
                                                    new org.springframework.ai.chat.messages.SystemMessage(systemText),
                                                    new org.springframework.ai.chat.messages.UserMessage(currentContent)));
                            reactor.core.publisher.Flux<org.springframework.ai.chat.model.ChatResponse> stream =
                                    chatModel.stream(streamPrompt);
                            if (streamParent != null) {
                                stream = stream.contextWrite(rctx -> rctx.put("micrometer.observation", streamParent));
                            }
                            StringBuilder buffer = new StringBuilder();
                            // OpenAI 兼容流式首 chunk 只有 role、content=null，getText() 为 null；
                            // Flux.map 不允许 mapper 返回 null（会抛 NPE），需转空串再按空串过滤
                            for (String token : stream.map(r -> {
                                    String text = r.getResult().getOutput().getText();
                                    return text != null ? text : "";
                                }).toIterable()) {
                                if (!token.isEmpty()) {
                                    buffer.append(token);
                                    SseUtils.safeSend(emitter, SseUtils.escapeJson(token));
                                }
                            }

                            String fullResponse = buffer.toString();
                            log.info("[Phase1] AI 流式回复完成, length={}", fullResponse.length());
                            phase1.attribute("stream_len", String.valueOf(fullResponse.length()));

                            // 后处理：AfterAiHookChain → AiResponseRouter（观测：agent.decision）
                            try (AgentSpan decision = agentTracer.start(AgentSpanSpec.DECISION, null)) {
                                HookResult afterResult = afterAiHookChain.execute(finalContent, fullResponse, ctx);
                                decision.attribute("decision", String.valueOf(afterResult.getDecision()));
                                if (afterResult.getHookName() != null) {
                                    decision.attribute("hook.name", afterResult.getHookName());
                                }
                                // 内容已逐 token 推送，通知路由跳过重复发送
                                responseRouter.route(afterResult, finalContent, fullResponse, ctx, emitter, true);

                                // 历史会话：PASS/REPLACE 在此落库。
                                // PLANNING 由 TaskPlanner 完成时记录最终合并答案；BLOCK 不落库（用户看到的是阻断原因，非成功回合）。
                                if (afterResult.isPass()) {
                                    recordTurnBestEffort(userId, conversationId, content, fullResponse);
                                } else if (afterResult.isReplace()) {
                                    recordTurnBestEffort(userId, conversationId, content, afterResult.getReplacedText());
                                }
                            }
                            return; // 成功，退出
                        } catch (Exception e) {
                            lastError = e;
                            log.warn("AI 流式调用失败 [attempt={}/{}]", attempt, maxAttempts, e);
                            if (attempt < maxAttempts) {
                                // 把错误喂给 AI，让 AI 重试生成回复
                                currentContent = finalContent + "\n\n[系统提示] 上一步调用因以下异常失败，请重试："
                                        + e.getClass().getSimpleName() + ": " + e.getMessage();
                            }
                        }
                    }
                    phase1.status("FAILED");
                }

                // 所有重试耗尽，给用户友好提示而非原始异常（完整堆栈已在上方 warn 日志记录）
                String friendlyMsg = "抱歉，AI 服务暂时不可用（" + errorSummary(lastError) + "），请稍后再试。";
                SseUtils.safeSend(emitter, SseUtils.progressEvent(SseEventConstants.STAGE_MERGING, SseEventConstants.TEXT_MERGING_DONE));
                SseUtils.safeSend(emitter, SseUtils.errorEvent(friendlyMsg));
                emitter.complete();
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

    /**
     * 最佳努力记录历史回合：失败只记日志、绝不向上抛。
     * <p>
     * 原因：SSE 模式下该方法位于重试循环内，抛异常会被 retry catch 捕获 → 重跑 LLM 浪费 token；
     * JSON 模式下抛异常会中断整个响应。历史持久化是收尾附加能力，不能影响聊天主链路。
     * </p>
     */
    private void recordTurnBestEffort(Long userId, String conversationId, String userContent, String assistantContent) {
        try {
            if (userId != null && assistantContent != null && !assistantContent.isBlank()) {
                historyService.recordTurn(userId, conversationId, userContent, assistantContent);
            }
        } catch (Exception e) {
            log.error("记录会话历史失败, conversationId={}", conversationId, e);
        }
    }

    /**
     * 处理 Hook 链的决策结果
     *
     * @param result         Hook 链决策
     * @param content        原始用户输入
     * @param conversationId 会话 ID
     * @return 替换后的文本（可用于 LLM 调用），若 BLOCK 则返回 null
     */
    private String processHookResult(HookResult result, String content, String conversationId) {
        switch (result.getDecision()) {
            case BLOCK -> {
                log.warn("Prompt 被拦截 [reason={}, hook={}]", result.getReason(), result.getHookName());
                return null;
            }
            case REPLACE -> {
                log.info("Prompt 被替换 [hook={}]", result.getHookName());
                // 如果有清洗后的历史，替换 ChatMemory 中的内容
                if (result.getReplacedHistory() != null) {
                    chatMemory.clear(conversationId);
                    chatMemory.add(conversationId, result.getReplacedHistory());
                    log.info("对话历史已清洗 [conversationId={}]", conversationId);
                }
                return result.getReplacedText();
            }
            case PASS -> {
                return content;
            }
            default -> {
                return content;
            }
        }
    }

    /**
     * 生成给用户的异常摘要：取根因消息首行并截断到 80 字符。
     * <p>
     * 完整堆栈只进日志，避免把内部 SQL/框架细节直接暴露给用户。
     * </p>
     */
    private static String errorSummary(Throwable t) {
        if (t == null) return "未知错误";
        Throwable root = t;
        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        String msg = root.getMessage();
        if (msg == null || msg.isBlank()) {
            return root.getClass().getSimpleName();
        }
        String firstLine = msg.split("\n", 2)[0];
        return firstLine.length() > 80 ? firstLine.substring(0, 80) : firstLine;
    }

}
