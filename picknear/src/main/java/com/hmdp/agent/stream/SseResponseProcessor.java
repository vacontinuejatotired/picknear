package com.hmdp.agent.stream;

import com.hmdp.agent.context.AgentContext;
import com.hmdp.agent.history.HistoryRecorder;
import com.hmdp.agent.hook.AfterAiHookChain;
import com.hmdp.agent.hook.HookResult;
import com.hmdp.agent.observability.api.AgentSpan;
import com.hmdp.agent.observability.api.AgentTracer;
import com.hmdp.agent.observability.model.AgentField;
import com.hmdp.agent.observability.model.AgentSpanSpec;
import com.hmdp.agent.response.AiResponseRouter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * SSE 回复后处理器 — AfterAiHook 链 + 决策观测 + 响应路由 + 历史落库。
 * <p>
 * 从 AiServiceImpl 异步段拆出（拆分第 3 步）：内容已逐 token 推送完毕后的收尾编排，
 * 职责单一、可独立单测。
 * </p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SseResponseProcessor {

    private final AfterAiHookChain afterAiHookChain;
    private final AiResponseRouter responseRouter;
    private final AgentTracer agentTracer;
    private final HistoryRecorder historyRecorder;

    /**
     * 对一次完整流式回复执行后处理（观测：agent.decision）。
     *
     * @param ctx              请求级 AgentContext（含 userId/conversationId）
     * @param originalContent  原始用户输入（历史落库用）
     * @param finalContent     决策后的 LLM 输入
     * @param fullResponse     完整回复文本（已逐 token 推送）
     * @param emitter          SSE 输出（路由通知跳过重复发送）
     */
    public void process(AgentContext ctx, String originalContent, String finalContent,
                        String fullResponse, SseEmitter emitter) {
        try (AgentSpan decision = agentTracer.start(AgentSpanSpec.DECISION, null)) {
            HookResult afterResult = afterAiHookChain.execute(finalContent, fullResponse, ctx);
            decision.set(AgentField.DECISION, String.valueOf(afterResult.getDecision()));
            if (afterResult.getHookName() != null) {
                decision.set(AgentField.HOOK_NAME, afterResult.getHookName());
            }
            // 内容已逐 token 推送，通知路由跳过重复发送
            responseRouter.route(afterResult, finalContent, fullResponse, ctx, emitter, true);

            // 历史会话：PASS/REPLACE 在此落库。
            // PLANNING 由 TaskPlanner 完成时记录最终合并答案；BLOCK 不落库（用户看到的是阻断原因，非成功回合）。
            if (afterResult.isPass()) {
                historyRecorder.recordBestEffort(ctx.userId(), ctx.conversationId(),
                        originalContent, fullResponse);
            } else if (afterResult.isReplace()) {
                historyRecorder.recordBestEffort(ctx.userId(), ctx.conversationId(),
                        originalContent, afterResult.getReplacedText());
            }
        }
    }
}
