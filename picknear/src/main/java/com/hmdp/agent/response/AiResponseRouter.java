package com.hmdp.agent.response;

import com.hmdp.agent.context.AgentContext;
import com.hmdp.agent.orchestration.TaskPlanner;
import com.hmdp.agent.stream.SseUtils;
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
 * <p>
 * 注意：本类不再显式结束根 span——SSE 结束收敛到 {@code ObservedSseEmitter}
 * （complete/completeWithError/容器回调/兜底 TTL 任一路径统一结束，2026-08-04 断链修复）。
 * </p>
 */
@Slf4j
@Component
public class AiResponseRouter {

    @Resource
    private TaskPlanner taskPlanner;

    /**
     * 路由后处理决策（兼容旧调用方，默认 contentAlreadyStreamed=false）。
     */
    public void route(HookResult result, String input, String aiResponse,
                      AgentContext ctx, SseEmitter emitter) {
        route(result, input, aiResponse, ctx, emitter, false);
    }

    /**
     * 路由后处理决策（流式感知）。
     *
     * @param result              AfterAiHook 链的决策结果
     * @param input               原始用户输入
     * @param aiResponse          LLM 完整回复内容
     * @param ctx                 请求级 AgentContext
     * @param emitter             SSE 发射器
     * @param contentStreamed     内容是否已通过流式逐 token 推送给客户端。
     *                            true 时 PASS 决策不再重复发送内容。
     */
    public void route(HookResult result, String input, String aiResponse,
                      AgentContext ctx, SseEmitter emitter, boolean contentStreamed) {
        try {
            switch (result.getDecision()) {
                case BLOCK -> {
                    log.info("路由: BLOCK [reason={}]", result.getReason());
                    SseUtils.safeSend(emitter, SseUtils.errorEvent(result.getReason()));
                    emitter.complete();
                }
                case REPLACE -> {
                    log.info("路由: REPLACE");
                    SseUtils.safeSend(emitter, SseUtils.escapeJson(result.getReplacedText()));
                    emitter.complete();
                }
                case PLANNING -> {
                    log.info("路由: PLANNING → TaskPlanner");
                    taskPlanner.submit(input, aiResponse, ctx, emitter);
                }
                default -> {
                    // PASS：内容已流式推送则跳过，前端 already 有逐 token 拼接的文本
                    if (!contentStreamed) {
                        SseUtils.safeSend(emitter, SseUtils.escapeJson(aiResponse));
                    }
                    emitter.complete();
                }
            }
        } catch (Exception e) {
            // 终态后 send 抛 ISE/IO（连接已断）已被 safeSend 吞掉，不会走到这；
            // 此处兜底非 send 类异常，避免逃逸到 AiServiceImpl 重试循环（浪费 token）
            log.error("AiResponseRouter 异常", e);
            SseUtils.safeSend(emitter, SseUtils.errorEvent(e.getMessage()));
            emitter.complete();
        }
    }
}
