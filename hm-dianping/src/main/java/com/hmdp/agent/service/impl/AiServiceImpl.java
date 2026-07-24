package com.hmdp.agent.service.impl;

import com.hmdp.agent.response.AiResponseRouter;
import com.hmdp.agent.service.AiService;
import com.hmdp.agent.tool.ToolBeanCollector;
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

        // 4. 异步执行 AI 调用（使用可能被替换后的 finalContent）
        CompletableFuture.runAsync(() -> {
            int maxAttempts = 3;
            Exception lastError = null;
            String currentContent = finalContent;
            for (int attempt = 1; attempt <= maxAttempts; attempt++) {
                try {
                    ChatClientRequestSpec prompt = chatClient.prompt()
                            .user(currentContent);
                    String result = prompt.call().content();
                    log.info("[Phase1] AI 初次回复, result={}", result);

                    // 后处理：AfterAiHookChain → AiResponseRouter
                    HookResult afterResult = afterAiHookChain.execute(finalContent, result, ctx);
                    responseRouter.route(afterResult, finalContent, result, ctx, emitter);
                    return; // 成功，退出
                } catch (Exception e) {
                    lastError = e;
                    log.warn("AI 调用失败 [attempt={}/{}]", attempt, maxAttempts, e);
                    if (attempt < maxAttempts) {
                        // 把错误喂给 AI，让 AI 重试生成回复
                        currentContent = finalContent + "\n\n[系统提示] 上一步调用因以下异常失败，请重试："
                                + e.getClass().getSimpleName() + ": " + e.getMessage();
                    }
                }
            }
            // 所有重试耗尽，给用户友好提示而非原始异常
            String friendlyMsg = "抱歉，AI 服务暂时不可用（" + lastError.getMessage() + "），请稍后再试。";
            SseUtils.safeSend(emitter, SseUtils.progressEvent("merging", "结论生成完成"));
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

}
