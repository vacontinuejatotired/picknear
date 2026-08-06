package com.hmdp.agent.service;

import com.hmdp.agent.entity.AgentApproval;
import com.hmdp.agent.task.TaskSnapshot;

/**
 * 工具调用审批服务 — CONFIRM 真暂停的持久化与状态流转。
 */
public interface ApprovalService {

    /**
     * 创建审批记录（best-effort：DB 失败由调用方降级到内存快照）。
     *
     * @return 生成的 confirmId（形如 cfm_xxx）；持久化失败返回 null
     */
    String createApproval(TaskSnapshot snapshot);

    /** 按 confirmId 查询（带归属校验） */
    AgentApproval getByConfirmId(String confirmId, Long userId);

    /** 原子 CAS 通过审批（pending → approved）；返回结果枚举，APPROVED 表示本次真正生效 */
    ApprovalDecisionResult markApproved(String confirmId, Long userId);

    /** 拒绝审批（pending → rejected） */
    ApprovalDecisionResult markRejected(String confirmId, Long userId);

    /** 标记为已过期（pending → expired，sweeper / 懒过期兜底） */
    boolean markExpired(String confirmId);

    /** 审批通过后工具已实际执行（approved 且未执行 → 记 executed_at） */
    boolean markExecuted(String confirmId, Long userId);

    /** 定时清扫：所有已过期的 pending 记录 → expired */
    void expireOverdue();

    /** 审批决策结果枚举（携带面向用户的提示文案） */
    enum ApprovalDecisionResult {
        APPROVED("确认成功"),
        REJECTED("已拒绝"),
        NOT_FOUND("审批记录不存在或无权操作"),
        NOT_PENDING("该审批已处理"),
        EXPIRED("确认已过期，请重新发起");

        private final String message;

        ApprovalDecisionResult(String message) {
            this.message = message;
        }

        public String getMessage() {
            return message;
        }
    }
}
