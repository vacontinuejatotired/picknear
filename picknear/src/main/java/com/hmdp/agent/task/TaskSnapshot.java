package com.hmdp.agent.task;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hmdp.agent.entity.AgentApproval;
import lombok.Data;

import java.util.List;

/**
 * 任务快照，用于 CONFIRM 续跑。
 * <p>
 * 当子任务队列中包含需要用户确认的工具时，
 * 将当前进度缓存到 {@link com.hmdp.prompthook.ChatContext#pendingSnapshot} 中，
 * 用户确认后通过 {@link TaskPlanner#resumeFromSnapshot(TaskSnapshot, com.hmdp.agent.hook.ChatContext, org.springframework.web.servlet.mvc.method.annotation.SseEmitter)} 恢复执行。
 * </p>
 */
@Data
public class TaskSnapshot {

    private static final ObjectMapper JSON = new ObjectMapper();

    /** 原始用户输入 */
    private String originalInput;

    /** 已收集的中间结果 */
    private String partialResponse;

    /** 已完成的工具名列表 */
    private List<String> completedTools;

    /** 当前轮次 */
    private int round;

    /** 待审批的工具名（CONFIRM 暂停时捕获，resume 时定位工具） */
    private String pendingToolName;

    /** 待审批的工具参数（JSON 字符串） */
    private String pendingToolArguments;

    /** 审批记录 confirmId（resume 后 markExecuted 用） */
    private String pendingConfirmId;

    /** 所属会话 ID（审批记录写入用，resume 重建 ChatContext 用） */
    private String conversationId;

    /** 所属用户 ID（异步线程无 UserHolder，审批记录写入用） */
    private Long userId;

    /**
     * 观测根 span（断链修复 2026-08-04）。
     * <p>
     * 快照恢复路径原实现传 null ctx → 异步线程 resume 跳过 → round 等 span 独立 trace。
     * 快照保存时携带根 span，恢复时重新挂载，保证恢复执行仍挂在会话树内。
     * </p>
     */
    private com.hmdp.agent.observability.api.AgentSpan rootSpan;

    /**
     * 从审批记录重建快照（用户确认后恢复执行）。
     * <p>
     * 注意：rootSpan 不可序列化不落库，resume 时用 confirm SSE 的新会话根 span。
     * </p>
     */
    public static TaskSnapshot fromApproval(AgentApproval a) {
        TaskSnapshot s = new TaskSnapshot();
        s.setOriginalInput(a.getOriginalInput());
        s.setPartialResponse(a.getPartialResponse());
        s.setRound(a.getRound() != null ? a.getRound() : 0);
        s.setPendingToolName(a.getToolName());
        s.setPendingToolArguments(a.getToolArguments());
        s.setPendingConfirmId(a.getConfirmId());
        s.setConversationId(a.getConversationId());
        s.setUserId(a.getUserId());
        if (a.getCompletedTools() != null && !a.getCompletedTools().isBlank()) {
            try {
                s.setCompletedTools(JSON.readValue(a.getCompletedTools(),
                        new TypeReference<List<String>>() {}));
            } catch (Exception e) {
                s.setCompletedTools(List.of());
            }
        } else {
            s.setCompletedTools(List.of());
        }
        return s;
    }
}
