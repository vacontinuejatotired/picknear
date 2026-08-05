package com.hmdp.agent.task;

import lombok.Data;

import java.util.List;

/**
 * 任务快照，用于 CONFIRM 续跑。
 * <p>
 * 当子任务队列中包含需要用户确认的工具时，
 * 将当前进度缓存到 {@link com.hmdp.prompthook.ChatContext#pendingSnapshot} 中，
 * 用户确认后通过 {@link TaskPlanner#resumeFromSnapshot(TaskSnapshot, org.springframework.web.servlet.mvc.method.annotation.SseEmitter)} 恢复执行。
 * </p>
 */
@Data
public class TaskSnapshot {

    /** 原始用户输入 */
    private String originalInput;

    /** 已收集的中间结果 */
    private String partialResponse;

    /** 已完成的工具名列表 */
    private List<String> completedTools;

    /** 当前轮次 */
    private int round;

    /**
     * 观测根 span（断链修复 2026-08-04）。
     * <p>
     * 快照恢复路径原实现传 null ctx → 异步线程 resume 跳过 → round 等 span 独立 trace。
     * 快照保存时携带根 span，恢复时重新挂载，保证恢复执行仍挂在会话树内。
     * </p>
     */
    private com.hmdp.agent.observability.api.AgentSpan rootSpan;
}
