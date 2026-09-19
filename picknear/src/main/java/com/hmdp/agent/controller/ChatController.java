package com.hmdp.agent.controller;

import com.hmdp.dto.Result;
import com.hmdp.enums.ErrorCode;
import com.hmdp.agent.access.AgentAccessService;
import com.hmdp.agent.entity.AgentApproval;
import com.hmdp.agent.service.ApprovalService;
import com.hmdp.agent.service.ApprovalService.ApprovalDecisionResult;
import com.hmdp.agent.orchestration.confirm.ConfirmResumeService;
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

import java.util.Map;


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
    private ApprovalService approvalService;

    @Resource
    private ConfirmResumeService confirmResumeService;

    @Resource
    private AgentAccessService agentAccessService;

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
    @Operation(summary = "发送聊天消息（SSE 流式）", description =
            "SSE 流式：逐段推送 AI 回复 + [DONE] 标记（对话已废弃 JSON 模式，仅保留流式）")
    public SseEmitter chat(
            @Parameter(description = "聊天内容") @RequestParam String content,
            @Parameter(description = "会话 ID（首次不传，后端自动生成并返回）") @RequestParam(required = false) String conversationId) {

        return agentAccessService.send(content, conversationId);
    }

    /**
     * 双模判断：Accept 头是否要求 SSE 流式。
     */
    private static boolean isSse(String accept) {
        return accept != null && accept.contains(MediaType.TEXT_EVENT_STREAM_VALUE);
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
