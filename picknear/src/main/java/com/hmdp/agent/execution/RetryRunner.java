package com.hmdp.agent.execution;

import com.hmdp.agent.config.SubTaskProperties;
import com.hmdp.agent.guard.model.ConfirmRequiredException;
import com.hmdp.agent.subagent.loop.ToolExecutionStrategy;
import com.hmdp.agent.subagent.loop.SubAgentToolLoopContext;
import com.hmdp.agent.execution.model.ExecutionInput;
import com.hmdp.agent.prompt.PromptService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

/**
 * 重试执行器（带指数退避和总超时控制）。
 * <p>
 * 每次尝试委托 {@link ToolExecutionStrategy} 执行工具循环。
 * CONFIRM 审批信号原样抛出不重试。
 * </p>
 */
@Slf4j
@Component
public class RetryRunner {

    private final ToolExecutionStrategy toolLoop;
    private final PromptService promptService;

    public RetryRunner(ToolExecutionStrategy toolLoop, PromptService promptService) {
        this.toolLoop = toolLoop;
        this.promptService = promptService;
    }

    public String executeWithRetry(String systemText, String prompt, ExecutionInput plan,
                                   ToolCallback[] callbacks,
                                   SubTaskProperties props, long roundStartMs,
                                   Long userId, String conversationId) {
        int maxRetries = props.getMaxRetries();
        Exception lastError = null;
        String currentPrompt = prompt;

        for (int attempt = 1; attempt <= maxRetries; attempt++) {
            if (System.currentTimeMillis() - roundStartMs > props.getTotalTimeout().toMillis()) {
                log.warn("[SubAgent] 执行总超时 [attempt={}/{}, elapsed>{}ms]",
                        attempt, maxRetries, props.getTotalTimeout().toMillis());
                break;
            }

            try {
                log.info("[SubAgent] 请求 attempt={}/{} 执行prompt字符数={}",
                        attempt, maxRetries, currentPrompt != null ? currentPrompt.length() : 0);
                Map<String, Object> toolCtx = new HashMap<>();
                if (userId != null) {
                    toolCtx.put("userId", userId);
                }
                if (conversationId != null && !conversationId.isBlank()) {
                    toolCtx.put("conversationId", conversationId);
                }
                SubAgentToolLoopContext ctx = new SubAgentToolLoopContext(
                        Arrays.asList(callbacks), systemText, currentPrompt, plan, promptService,
                        toolCtx, props);
                String content = toolLoop.execute(ctx);
                log.info("[SubAgent] 调用成功 [attempt={}/{}]", attempt, maxRetries);
                return content;
            } catch (ConfirmRequiredException e) {
                throw e;
            } catch (Exception e) {
                lastError = e;
                log.warn("[SubAgent] 调用失败 [attempt={}/{}], err={}",
                        attempt, maxRetries, e.getMessage());

                if (attempt < maxRetries) {
                    long backoffMs = props.getRetryBackoff().toMillis() * (long) Math.pow(2, attempt - 1);
                    log.info("[SubAgent] {}ms 后重试...", backoffMs);
                    try {
                        Thread.sleep(backoffMs);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                    currentPrompt = prompt + "\n\n[系统提示] 上一次调用失败，原因：" + e.getMessage() + "。请重试。";
                }
            }
        }
        log.error("[SubAgent] 重试耗尽 [maxRetries={}]", maxRetries, lastError);
        return null;
    }
}
