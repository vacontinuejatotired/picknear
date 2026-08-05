package com.hmdp.agent.controller;

import com.hmdp.dto.Result;
import com.hmdp.agent.observability.api.AgentSpan;
import com.hmdp.agent.observability.api.AgentTracer;
import com.hmdp.agent.observability.api.ObservedSseEmitter;
import com.hmdp.agent.service.AiService;
import com.hmdp.agent.util.SseUtils;
import com.hmdp.utils.UserHolder;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;

import org.springframework.http.MediaType;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;


/**
 * <p>
 * 聊天控制器 — AI 对话，支持普通 JSON 和 SSE 流式双模
 * </p>
 */
@Slf4j
@RestController
@RequestMapping("/agent")
@Tag(name = "聊天模块", description = "聊天功能接口")
public class ChatController {

    /** SSE 超时时间：30 分钟（AI 长思考场景） */
    private static final long SSE_TIMEOUT = 30 * 60 * 1000L;

    /** ObservedSseEmitter 兜底 TTL：容器超时(30min)先触发 onTimeout→finish；此值为最后防线，须 > SSE_TIMEOUT */
    private static final long SSE_GUARD_TIMEOUT = 32 * 60 * 1000L;

    @Resource
    private AiService aiService;

    @Resource
    private AgentTracer agentTracer;

    @Resource
    private TaskScheduler taskScheduler;

    /**
     * 发送聊天消息 — 双模端点
     * <p>
     * 根据 {@code Accept} 请求头自动切换响应格式：
     * <ul>
     *   <li>{@code Accept: text/event-stream} → SSE 流式响应</li>
     *   <li>其他 / 无 → 普通 JSON 响应</li>
     * </ul>
     */
    @PostMapping("/string/send")
    @Operation(summary = "发送聊天消息（双模）", description =
            "JSON 模式返回 Result 信封；SSE 模式（Accept: text/event-stream）逐段推送 AI 回复 + [DONE] 标记")
    public Object chat(
            @Parameter(description = "聊天内容") @RequestParam String content,
            @Parameter(description = "客户端期望的响应格式") @RequestHeader(value = "Accept", required = false, defaultValue = "") String accept,
            @Parameter(description = "会话 ID（首次不传，后端自动生成并返回）") @RequestParam(required = false) String conversationId) {

        // 首次调用无 conversationId → 自动生成；后续调用由前端传入
        if (conversationId == null || conversationId.isBlank()) {
            conversationId = UUID.randomUUID().toString().replace("-", "");
            log.info("新建会话 [conversationId={}]", conversationId);
        } else {
            log.info("续传会话 [conversationId={}]", conversationId);
        }

        // SSE 模式
        if (accept != null && accept.contains(MediaType.TEXT_EVENT_STREAM_VALUE)) {
            log.info("SSE 模式：content={}", content);

            // 观测：会话根 span（agent.session）。先创建 root 再构造 emitter（顺序：root 必须非 null）
            AgentSpan root = agentTracer.startSession(conversationId,
                    String.valueOf(UserHolder.getUserId()));

            // 断链修复（2026-08-04）：ObservedSseEmitter 将 SSE 生命周期与会话根 span 绑定——
            // complete / completeWithError / 容器超时 / 容器错误 / 兜底 TTL 任一路径都收敛结束根 span。
            // 原因：onCompletion 回调在客户端断开时可能不触发（生产实测），根 span 永不 end/导出 → 平铺。
            // 三回调只留日志（end/finish 收敛到包装类，幂等）。
            SseEmitter emitter = new ObservedSseEmitter(SSE_TIMEOUT, root, taskScheduler, SSE_GUARD_TIMEOUT);

            emitter.onCompletion(() ->
                    log.info("SSE 流完成, content={}, thread={}", content, Thread.currentThread().getName()));
            emitter.onTimeout(() -> log.warn("SSE 流超时, content={}", content));
            emitter.onError(ex -> log.error("SSE 流异常, content={}", content, ex));

            // 先推送 conversationId（JSON 格式，前端据此识别为元事件，不混入回答文本）
            try {
                emitter.send(SseEmitter.event()
                        .data("{\"type\":\"meta\",\"conversationId\":\"" + conversationId + "\"}"));
            } catch (IOException e) {
                log.error("推送 conversationId 失败", e);
                emitter.completeWithError(e);
                return null;
            }

            // 委托 AiService 异步推送。
            // 兜底 try/catch：SSE 响应已提交，任何异常都必须转为 SSE error 事件，
            // 否则会逃逸到 WebExceptionAdvice 往已提交的流里写 JSON，前端收不到提示。
            try {
                aiService.chatWithToolcall(content, conversationId, emitter, root);
            } catch (Exception e) {
                log.error("SSE 会话初始化异常，content={}", content, e);
                SseUtils.safeSend(emitter, SseUtils.errorEvent("抱歉，AI 服务暂时不可用，请稍后再试。"));
                emitter.complete();
            }
            return emitter;
        }

        // JSON 模式
        log.info("JSON 模式：content={}，accept={}", content, accept);
        String result = aiService.chatReturnStringResult(content, conversationId);

        // 返回内容 + conversationId，供前端保存并下次传入
        Map<String, Object> data = new HashMap<>();
        data.put("content", result);
        data.put("conversationId", conversationId);
        return Result.ok(data);
    }

}