package com.hmdp.agent.response;

import com.hmdp.agent.task.TaskPlanner;
import com.hmdp.agent.util.SseUtils;
import com.hmdp.agent.hook.ChatContext;
import com.hmdp.agent.hook.HookResult;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * AI 回复后处理路由器。
 * <p>
 * 根据 {@link HookResult.Decision} 分发到对应处理器，
 * 避免 {@code AiServiceImpl} 承担路由职责。
 * </p>
 *
 * <pre>
 * 路由规则：
 * - BLOCK    → 推送错误消息，结束 SSE
 * - REPLACE  → 推送替换文本，结束 SSE
 * - PLANNING → 委托 TaskPlanner 异步规划执行
 * - PASS     → 推送原始回复，结束 SSE
 * </pre>
 */
@Slf4j
@Component
public class AiResponseRouter {

    @Resource
    private TaskPlanner taskPlanner;

    /**
     * 路由后处理决策。
     *
     * @param result     AfterAiHook 链的决策结果
     * @param input      原始用户输入
     * @param aiResponse LLM 回复内容
     * @param ctx        对话上下文
     * @param emitter    SSE 发射器
     */
    public void route(HookResult result, String input, String aiResponse,
                      ChatContext ctx, SseEmitter emitter) {
        try {
            switch (result.getDecision()) {
                case BLOCK -> {
                    log.info("路由: BLOCK [reason={}]", result.getReason());
                    emitter.send(SseEmitter.event().data(SseUtils.errorEvent(result.getReason())));
                    emitter.complete();
                }
                case REPLACE -> {
                    log.info("路由: REPLACE");
                    emitter.send(SseEmitter.event().data(SseUtils.escapeJson(result.getReplacedText())));
                    emitter.complete();
                }
                case PLANNING -> {
                    log.info("路由: PLANNING → TaskPlanner");
                    taskPlanner.planAndExecuteAsync(input, aiResponse, ctx, emitter);
                }
                default -> {
                    emitter.send(SseEmitter.event().data(SseUtils.escapeJson(aiResponse)));
                    emitter.complete();
                }
            }
        } catch (Exception e) {
            log.error("AiResponseRouter 异常", e);
            try {
                emitter.send(SseEmitter.event().data(SseUtils.errorEvent(e.getMessage())));
                emitter.complete();
            } catch (Exception ignored) {
                emitter.completeWithError(e);
            }
        }
    }
}
