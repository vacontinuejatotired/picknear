package com.hmdp.agent.subagent;

import com.hmdp.agent.config.SubTaskProperties;
import com.hmdp.agent.guard.ConfirmRequiredException;
import com.hmdp.agent.subagent.loop.SubAgentToolLoop;
import com.hmdp.agent.subagent.loop.SubAgentToolLoopContext;
import com.hmdp.agent.subagent.model.SubTaskPlan;
import com.hmdp.agent.prompt.PromptService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

/**
 * 子 Agent 重试执行器（从 SubTaskAgent 拆出）。
 * <p>
 * 带指数退避和总超时控制的调用编排：retryBackoff 为基础间隔，每次翻倍（1s → 2s → 4s），
 * totalTimeout 为整个调用的总超时（含重试），超时直接终止。
 * 每次尝试委托 {@link SubAgentToolLoop} 执行工具循环。
 * </p>
 * <p>
 * CONFIRM 审批信号（{@link ConfirmRequiredException}）原样抛出不重试——一路冒泡到
 * TaskPlanner 的专用 catch 生成审批记录并暂停规划。
 * </p>
 */
@Slf4j
@Component
public class SubAgentRetryRunner {

    private final SubAgentToolLoop toolLoop;
    private final PromptService promptService;

    public SubAgentRetryRunner(SubAgentToolLoop toolLoop, PromptService promptService) {
        this.toolLoop = toolLoop;
        this.promptService = promptService;
    }

    /**
     * 带退避重试调用（含总超时保护），携带 userId / conversationId 作为 ToolContext。
     *
     * @return LLM 完整回复；重试耗尽或超时返回 null
     */
    public String executeWithRetry(String systemText, String prompt, SubTaskPlan plan,
                                   ToolCallback[] callbacks,
                                   SubTaskProperties props, long roundStartMs,
                                   Long userId, String conversationId) {
        int maxRetries = props.getMaxRetries();
        Exception lastError = null;
        String currentPrompt = prompt;

        for (int attempt = 1; attempt <= maxRetries; attempt++) {
            // 总超时检查
            if (System.currentTimeMillis() - roundStartMs > props.getTotalTimeout().toMillis()) {
                log.warn("[SubAgent] 执行总超时 [attempt={}/{}, elapsed>{}ms]",
                        attempt, maxRetries, props.getTotalTimeout().toMillis());
                break;
            }

            try {
                // 取证（设计文档 §8.1）：记录每轮请求的执行 prompt 字符数，
                // 对照 Langfuse trace 可定位上下文膨胀
                log.info("[SubAgent] 请求 attempt={}/{} 执行prompt字符数={}",
                        attempt, maxRetries, currentPrompt != null ? currentPrompt.length() : 0);
                // 将 userId / conversationId 以 ToolContext 传递，Guard 层才能获取到
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
                // CONFIRM 审批信号：立即原样抛出（不重试、不把确认提示注入下次 prompt），
                // 一路冒泡到 TaskPlanner 的专用 catch 生成审批记录并暂停规划
                throw e;
            } catch (Exception e) {
                lastError = e;
                log.warn("[SubAgent] 调用失败 [attempt={}/{}], err={}",
                        attempt, maxRetries, e.getMessage());

                if (attempt < maxRetries) {
                    // 指数退避
                    long backoffMs = props.getRetryBackoff().toMillis() * (long) Math.pow(2, attempt - 1);
                    log.info("[SubAgent] {}ms 后重试...", backoffMs);
                    try {
                        Thread.sleep(backoffMs);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                    // 标准化错误注入格式
                    currentPrompt = prompt + "\n\n[系统提示] 上一次调用失败，原因：" + e.getMessage() + "。请重试。";
                }
            }
        }
        log.error("[SubAgent] 重试耗尽 [maxRetries={}]", maxRetries, lastError);
        return null;
    }
}
