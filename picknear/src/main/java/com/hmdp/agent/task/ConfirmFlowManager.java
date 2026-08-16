package com.hmdp.agent.task;

import com.hmdp.agent.context.AgentContext;
import com.hmdp.agent.guard.GuardedToolCallback;
import com.hmdp.agent.guard.model.ConfirmRequiredException;
import com.hmdp.agent.guard.model.ToolInvocationContext;
import com.hmdp.agent.service.ApprovalService;
import com.hmdp.agent.tool.ToolBeanCollector;
import com.hmdp.agent.util.SseEventConstants;
import com.hmdp.agent.util.SseUtils;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * CONFIRM 审批中间态管理器（从 TaskPlanner 拆出）。
 * <p>
 * 围绕 {@link TaskSnapshot} 快照的创建与消费，管理「暂停 → 审批 → 恢复执行」的中间态：
 * <ul>
 *   <li>{@link #pause} — 暂停：构建快照 → 暂存到 AgentContext.attributes → 建审批记录 → 推确认事件</li>
 *   <li>{@link #executeApprovedTool} — 恢复执行：callBypass 绕过守卫直调待审批工具 + markExecuted</li>
 *   <li>{@link #seedCompletedHistory} — 预置已完成历史（防二次审批）</li>
 * </ul>
 * </p>
 * <p>
 * 边界：本组件只做中间态本身，不依赖主循环（恢复后的续跑由调用方编排），
 * 避免与编排层形成循环依赖。
 * </p>
 */
@Slf4j
@Component
public class ConfirmFlowManager {

    @Resource
    private ToolBeanCollector toolBeanCollector;

    @Resource
    private ApprovalService approvalService;

    /**
     * CONFIRM 暂停处理：保存快照 → 建审批记录 → 推确认事件 → 终止本轮流。
     * <p>
     * 返回 currentResponse，由调用方（completeTurn）识别暂停态（pendingSnapshot != null）
     * 跳过尾文本推送与历史落库，保证确认事件是流中最后一个数据。
     * </p>
     */
    public String pause(ConfirmRequiredException e, String input, String currentResponse,
                        TaskReport history, int round, AgentContext ctx, SseEmitter emitter) {
        ToolInvocationContext ic = e.getContext();
        TaskSnapshot snapshot = new TaskSnapshot();
        snapshot.setOriginalInput(input);
        snapshot.setPartialResponse(currentResponse);
        snapshot.setCompletedTools(history.getCompleted().stream()
                .map(SubTask::getToolName).toList());
        snapshot.setRound(round);
        snapshot.setPendingToolName(ic.getToolName());
        snapshot.setPendingToolArguments(ic.getArguments());
        // 上下文来源：AgentContext 优先，参数 ctx 兜底，最后取守卫异常携带的调用上下文
        snapshot.setConversationId(AgentContextResolver.resolveConversationId(ctx, ic.getConversationId()));
        snapshot.setUserId(AgentContextResolver.resolveUserId(ctx, ic.getUserId()));
        snapshot.setRootSpan(AgentContextResolver.resolveRootSpan(ctx, null));
        if (ctx != null) {
            ctx.putAttribute(AgentContext.ATTR_PENDING_SNAPSHOT, snapshot);
        }

        String reason = e.getReason() != null ? e.getReason() : "该操作需要你的确认才能执行";
        // best-effort 持久化：DB 失败返回 null，仍推确认事件（前端只提示、无法续流，可接受降级）
        String confirmId = approvalService.createApproval(snapshot);
        SseUtils.safeSend(emitter, SseUtils.confirmEvent(
                confirmId != null ? confirmId : "",
                ic.getToolName(), reason, ic.getArguments()));
        log.info("CONFIRM 暂停规划 [tool={}, confirmId={}, round={}]", ic.getToolName(), confirmId, round);
        return currentResponse;
    }

    /**
     * 执行已确认的工具（绕过守卫直调底层）。显式携带 userId / conversationId：
     * 恢复执行在异步线程、无 UserHolder，且数据权限切面从 ToolContext 取 userId。
     */
    public String executeApprovedTool(TaskSnapshot snapshot, AgentContext ctx, SseEmitter emitter) {
        String toolName = snapshot.getPendingToolName();
        if (toolName == null || toolName.isBlank()) {
            log.warn("快照缺少待审批工具名，无法恢复");
            return null;
        }
        ToolCallback cb = toolBeanCollector.getToolCallback(toolName);
        if (!(cb instanceof GuardedToolCallback guarded)) {
            log.error("待审批工具不存在或未包装: {}", toolName);
            SseUtils.safeSend(emitter, SseUtils.errorEvent("待确认工具不可用，无法继续"));
            return null;
        }
        // 上下文来源：AgentContext 优先，参数 ctx 兜底，最后取快照（跨请求持久化语义）
        Long uid = AgentContextResolver.resolveUserId(ctx, snapshot.getUserId());
        String cid = AgentContextResolver.resolveConversationId(ctx, snapshot.getConversationId());
        Map<String, Object> toolCtxMap = new HashMap<>();
        if (uid != null) toolCtxMap.put("userId", uid);
        if (cid != null && !cid.isBlank()) toolCtxMap.put("conversationId", cid);

        SseUtils.safeSend(emitter, SseUtils.stepEvent(toolName, SseEventConstants.TOOL_RUNNING));
        String result = guarded.callBypass(snapshot.getPendingToolArguments(), new ToolContext(toolCtxMap));
        SseUtils.safeSend(emitter, SseUtils.stepEvent(toolName, SseEventConstants.TOOL_COMPLETED));
        log.info("审批工具已执行 [tool={}, userId={}]", toolName, uid);
        if (snapshot.getPendingConfirmId() != null && uid != null) {
            approvalService.markExecuted(snapshot.getPendingConfirmId(), uid);
        }
        return result;
    }

    /**
     * 预置已完成工具到 history：completedTools ∪ 待审批工具（全部 COMPLETED）。
     * 目的：resume 后 decompose 的 validatePlan 依据 isCompleted 过滤，防二次审批。
     */
    public void seedCompletedHistory(TaskReport history, TaskSnapshot snapshot, String approvedToolResult) {
        if (snapshot.getCompletedTools() != null) {
            for (String tool : snapshot.getCompletedTools()) {
                if (tool == null || tool.isBlank()) continue;
                history.record(List.of(SubTask.builder()
                        .toolName(tool)
                        .type(TaskType.TOOL_CALL)
                        .status(SubTaskStatus.COMPLETED)
                        .result("(已完成)")
                        .build()));
            }
        }
        if (snapshot.getPendingToolName() != null) {
            history.record(List.of(SubTask.builder()
                    .toolName(snapshot.getPendingToolName())
                    .type(TaskType.TOOL_CALL)
                    .status(SubTaskStatus.COMPLETED)
                    .result(approvedToolResult != null ? approvedToolResult : "(已执行)")
                    .build()));
        }
    }
}
