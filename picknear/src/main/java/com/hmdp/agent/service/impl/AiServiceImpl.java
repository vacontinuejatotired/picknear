package com.hmdp.agent.service.impl;

import com.hmdp.agent.response.AiResponseRouter;
import com.hmdp.agent.service.AiService;
import com.hmdp.agent.tool.ToolBeanCollector;
import com.hmdp.agent.util.SseEventConstants;
import com.hmdp.agent.util.SseUtils;
import com.hmdp.agent.hook.AfterAiHookChain;
import com.hmdp.agent.hook.ChatContext;
import com.hmdp.agent.hook.HookResult;
import com.hmdp.agent.hook.PromptHookChain;
import com.hmdp.utils.UserHolder;

import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.ChatClient.ChatClientRequestSpec;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

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

    @Override
    public String chatReturnStringResult(String content, String conversationId) {
        log.info("AI 调用：{}", content);

        // 0. 将会话 ID 同步到工具收集器（供 GuardedToolCallback / RateLimitPolicy 使用）
        toolBeanCollector.setConversationId(conversationId);

        // 1. 构造 Hook 上下文
        ChatContext ctx = ChatContext.builder()
                .userId(UserHolder.getUserId())
                .conversationId(conversationId)
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

        // 4. 正常调用 LLM
        String result = chatClient.prompt().user(finalContent).call().content();
        log.info("AI 回复：{}", result);
        return result;
    }

    @Override
    public void chatWithToolcall(String content, String conversationId, SseEmitter emitter) {
        log.info("AI SSE 工具调用, content={}", content);
        try {
            doChatWithToolcall(content, conversationId, emitter);
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
    private void doChatWithToolcall(String content, String conversationId, SseEmitter emitter) {
        Long userId = UserHolder.getUserId();

        // 0. 将会话 ID 同步到工具收集器
        toolBeanCollector.setConversationId(conversationId);

        // 1. 构造 Hook 上下文（在主线程执行，UserHolder 有效）
        ChatContext ctx = ChatContext.builder()
                .userId(userId)
                .conversationId(conversationId)
                .history(chatMemory.get(conversationId))
                .build();

        // 2. 执行 Hook 链
        HookResult hookResult = promptHookChain.execute(content, ctx);

        // 3. 处理决策（仍在主线程）
        String finalContent = processHookResult(hookResult, content, conversationId);
        if (finalContent == null) {
            SseUtils.safeSend(emitter, SseUtils.errorEvent(hookResult.getReason()));
            emitter.complete();
            return;
        }

        // 4. 流式调用 AI（真正的逐 token 推送）
        CompletableFuture.runAsync(() -> {
            int maxAttempts = 3;
            Exception lastError = null;
            String currentContent = finalContent;
            for (int attempt = 1; attempt <= maxAttempts; attempt++) {
                try {
                    ChatClientRequestSpec prompt = chatClient.prompt()
                            .user(currentContent);

                    // 逐 token 流式推送：遍历 Flux，每收到一个 token 立即推 SSE
                    StringBuilder buffer = new StringBuilder();
                    for (String token : prompt.stream().content().toIterable()) {
                        if (token != null && !token.isEmpty()) {
                            buffer.append(token);
                            SseUtils.safeSend(emitter, SseUtils.escapeJson(token));
                        }
                    }

                    String fullResponse = buffer.toString();
                    log.info("[Phase1] AI 流式回复完成, length={}", fullResponse.length());

                    // 后处理：AfterAiHookChain → AiResponseRouter
                    HookResult afterResult = afterAiHookChain.execute(finalContent, fullResponse, ctx);
                    // 内容已逐 token 推送，通知路由跳过重复发送
                    responseRouter.route(afterResult, finalContent, fullResponse, ctx, emitter, true);
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
            // 所有重试耗尽，给用户友好提示而非原始异常（完整堆栈已在上方 warn 日志记录）
            String friendlyMsg = "抱歉，AI 服务暂时不可用（" + errorSummary(lastError) + "），请稍后再试。";
            SseUtils.safeSend(emitter, SseUtils.progressEvent(SseEventConstants.STAGE_MERGING, SseEventConstants.TEXT_MERGING_DONE));
            SseUtils.safeSend(emitter, SseUtils.errorEvent(friendlyMsg));
            emitter.complete();
        }, aiTaskExecutor);
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
