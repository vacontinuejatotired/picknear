package com.hmdp.agent.orchestration.confirm;

import com.hmdp.agent.context.AgentContext;
import com.hmdp.agent.guard.GuardedToolCallback;
import com.hmdp.agent.guard.model.ConfirmRequiredException;
import com.hmdp.agent.guard.model.ToolInvocationContext;
import com.hmdp.agent.service.ApprovalService;
import com.hmdp.agent.orchestration.support.AgentContextResolver;
import com.hmdp.agent.plan.model.SubTask;
import com.hmdp.agent.plan.model.SubTaskStatus;
import com.hmdp.agent.plan.model.TaskReport;
import com.hmdp.agent.task.model.TaskSnapshot;
import com.hmdp.agent.plan.model.TaskType;
import com.hmdp.agent.tool.ToolBeanCollector;
import com.hmdp.agent.stream.SseEventConstants;
import com.hmdp.agent.stream.SseUtils;
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
 * CONFIRM 审批中间态管理器。
 * <p>
 * 管理「暂停 → 审批 → 恢复执行」的中间态。
 * </p>
 */
@Slf4j
@Component
public class ConfirmFlowManager {

    @Resource
    private ToolBeanCollector toolBeanCollector;

    @Resource
    private ApprovalService approvalService;

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
        snapshot.setConversationId(AgentContextResolver.resolveConversationId(ctx, ic.getConversationId()));
        snapshot.setUserId(AgentContextResolver.resolveUserId(ctx, ic.getUserId()));
        snapshot.setRootSpan(AgentContextResolver.resolveRootSpan(ctx, null));
        if (ctx != null) {
            ctx.putAttribute(AgentContext.ATTR_PENDING_SNAPSHOT, snapshot);
        }

        String reason = e.getReason() != null ? e.getReason() : "该操作需要你的确认才能执行";
        String confirmId = approvalService.createApproval(snapshot);
        SseUtils.safeSend(emitter, SseUtils.confirmEvent(
                confirmId != null ? confirmId : "",
                ic.getToolName(), reason, ic.getArguments()));
        log.info("CONFIRM 暂停规划 [tool={}, confirmId={}, round={}]", ic.getToolName(), confirmId, round);
        return currentResponse;
    }

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
