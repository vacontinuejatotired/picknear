package com.hmdp.agent.controller;

import com.hmdp.dto.Result;
import com.hmdp.enums.ErrorCode;
import com.hmdp.agent.observability.api.AgentSpan;
import com.hmdp.agent.context.AgentContext;
import com.hmdp.agent.context.AgentContextHolder;
import com.hmdp.agent.entity.AgentApproval;
import com.hmdp.agent.service.AiService;
import com.hmdp.agent.service.ApprovalService;
import com.hmdp.agent.service.ApprovalService.ApprovalDecisionResult;
import com.hmdp.agent.stream.SseSessionFactory;
import com.hmdp.agent.stream.SseSessionFactory.ChatSseSession;
import com.hmdp.agent.task.ConfirmResumeService;
import com.hmdp.agent.util.SseUtils;
import com.hmdp.utils.UserHolder;

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
 * 聊天控制器 — AI 对话，支持普通 JSON 和 SSE 流式双模；CONFIRM 审批（确认/拒绝）
 * </p>
 */
@Slf4j
@RestController
@RequestMapping("/agent")
@Tag(name = "聊天模块", description = "聊天功能接口")
public class ChatController {

    /** SSE 超时与兜底 TTL 常量已收敛到 SseSessionFactory（chat/confirm 共用同一套装配） */
    @Resource
    private AiService aiService;

    @Resource
    private ApprovalService approvalService;

    @Resource
    private ConfirmResumeService confirmResumeService;

    @Resource
    private org.springframework.ai.chat.memory.ChatMemory chatMemory;

    @Resource
    private SseSessionFactory sseSessionFactory;

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
        if (isSse(accept)) {
            log.debug("SSE 模式：content={}", content);

            // 会话装配（root span + emitter + 断链修复约定收敛到 SseSessionFactory）
            ChatSseSession session =
                    sseSessionFactory.open(conversationId, UserHolder.getUserId());
            AgentSpan root = session.root();
            SseEmitter emitter = session.emitter();

            // 请求级 AgentContext：入口创建一次，同步段 Holder 读取、异步段 Propagator 自动传播。
            // history 在此拉取（与 PromptHookExecutor 原逻辑同源），Hook 链无需再查 chatMemory
            AgentContextHolder.set(AgentContext.builder()
                    .userId(UserHolder.getUserId())
                    .conversationId(conversationId)
                    .originalInput(content)
                    .history(chatMemory.get(conversationId))
                    .rootSpan(root)
                    .build());
            try {
                emitter.onCompletion(() ->
                        log.debug("SSE 流完成, thread={}", Thread.currentThread().getName()));
                emitter.onTimeout(() -> log.warn("SSE 流超时, content={}", brief(content)));
                emitter.onError(ex -> log.error("SSE 流异常, content={}", brief(content), ex));

                // 先推送 conversationId（JSON 格式，前端据此识别为元事件，不混入回答文本）
                try {
                    sseSessionFactory.sendConversationId(emitter, conversationId);
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
            } finally {
                // 与根 span 清理同点：请求线程的 AgentContext 在此清理（异步段由 Propagator 清理）
                AgentContextHolder.clear();
            }
        }

        // JSON 模式
        log.debug("JSON 模式：content={}，accept={}", content, accept);
        // 请求级 AgentContext：JSON 模式同步执行，无异步段，但保持入口创建语义统一
        AgentContextHolder.set(AgentContext.builder()
                .userId(UserHolder.getUserId())
                .conversationId(conversationId)
                .originalInput(content)
                .history(chatMemory.get(conversationId))
                .build());
        try {
            String result = aiService.chatReturnStringResult(content, conversationId);

            // 返回内容 + conversationId，供前端保存并下次传入
            Map<String, Object> data = new HashMap<>();
            data.put("content", result);
            data.put("conversationId", conversationId);
            return Result.ok(data);
        } finally {
            AgentContextHolder.clear();
        }
    }

    /**
     * 双模判断：Accept 头是否要求 SSE 流式。
     */
    private static boolean isSse(String accept) {
        return accept != null && accept.contains(MediaType.TEXT_EVENT_STREAM_VALUE);
    }

    /**
     * 日志脱敏：用户输入截断到 50 字符，避免全文落盘
     */
    private static String brief(String s) {
        if (s == null) return "null";
        return s.length() <= 50 ? s : s.substring(0, 50) + "...";
    }

    /**
     * CONFIRM 确认 — 双模端点。
     * <p>
     * 原子 CAS 通过审批后：
     * <ul>
     *   <li>{@code Accept: text/event-stream} → 打开新 SSE 续流，恢复执行待审批工具并继续规划</li>
     *   <li>其他 → JSON 200</li>
     * </ul>
     * 失败返回对应错误（已过期 / 已处理 / 无权操作），前端据此提示。
     * </p>
     */
    @PostMapping("/confirm")
    @Operation(summary = "确认待审批工具调用（双模）", description =
            "默认 JSON 200；Accept: text/event-stream 返回 SSE 续流（恢复执行 + 继续规划）")
    public Object confirm(
            @Parameter(description = "确认 ID") @RequestParam String confirmId,
            @Parameter(description = "客户端期望的响应格式") @RequestHeader(value = "Accept", required = false, defaultValue = "") String accept) {

        Long userId = UserHolder.getUserId();
        ApprovalDecisionResult decision = approvalService.markApproved(confirmId, userId);
        if (decision != ApprovalDecisionResult.APPROVED) {
            return Result.fail(decision.getMessage());
        }
        AgentApproval approval = approvalService.getByConfirmId(confirmId, userId);
        if (approval == null) {
            return Result.fail(ErrorCode.NOT_FOUND, "审批记录不存在");
        }

        // SSE 模式：续流恢复执行（会话装配/快照重建/AgentContext 重建下沉 ConfirmResumeService）
        if (isSse(accept)) {
            SseEmitter emitter = confirmResumeService.resume(approval, userId);
            return emitter; // null = 推送 conversationId 失败已 completeWithError，请求结束
        }

        log.info("确认成功：confirmId={}", confirmId);
        return Result.ok(Map.of("approved", true));
    }

    /**
     * CONFIRM 拒绝：pending → rejected，返回 JSON 200（操作已取消）。
     */
    @PostMapping("/reject")
    @Operation(summary = "拒绝待审批工具调用", description = "返回 JSON 200；已过期/已处理/无权操作返回对应错误")
    public Result reject(
            @Parameter(description = "确认 ID") @RequestParam String confirmId) {
        Long userId = UserHolder.getUserId();
        ApprovalDecisionResult decision = approvalService.markRejected(confirmId, userId);
        if (decision != ApprovalDecisionResult.REJECTED) {
            return Result.fail(decision.getMessage());
        }
        log.info("审批拒绝：confirmId={}", confirmId);
        return Result.ok();
    }

}