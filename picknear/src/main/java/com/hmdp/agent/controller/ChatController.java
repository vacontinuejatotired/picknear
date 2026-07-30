package com.hmdp.agent.controller;

import com.hmdp.dto.Result;
import com.hmdp.agent.service.AiService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;

import org.springframework.http.MediaType;
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

    @Resource
    private AiService aiService;

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
            SseEmitter emitter = new SseEmitter(SSE_TIMEOUT);

            // 注册回调以便日志追踪
            emitter.onCompletion(() -> log.info("SSE 流完成, content={}", content));
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

            // 委托 AiService 异步推送
            aiService.chatWithToolcall(content, conversationId, emitter);
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