package com.hmdp.agent.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hmdp.agent.config.PromptGuardProperties;
import com.hmdp.agent.entity.AgentApproval;
import com.hmdp.agent.mapper.AgentApprovalMapper;
import com.hmdp.agent.service.ApprovalService;
import com.hmdp.agent.task.TaskSnapshot;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * 工具调用审批服务实现。
 * <p>
 * 状态流转：pending → approved / rejected / expired；approved 且 executed_at 为空 → 可恢复执行。
 * 决策一律用记录里的 user_id（异步线程无 UserHolder）。
 * </p>
 */
@Slf4j
@Service
public class ApprovalServiceImpl implements ApprovalService {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final int CONFIRM_ID_RANDOM_LEN = 24;

    @Resource
    private AgentApprovalMapper approvalMapper;

    @Resource
    private PromptGuardProperties promptGuardProperties;

    @Resource
    private TaskScheduler taskScheduler;

    @Override
    public String createApproval(TaskSnapshot snapshot) {
        try {
            AgentApproval a = new AgentApproval();
            a.setConfirmId(newConfirmId());
            a.setConversationId(snapshot.getConversationId());
            a.setUserId(snapshot.getUserId());
            a.setToolName(snapshot.getPendingToolName());
            a.setToolArguments(snapshot.getPendingToolArguments() != null
                    ? snapshot.getPendingToolArguments() : "{}");
            a.setStatus(AgentApproval.STATUS_PENDING);
            a.setExpiredAt(LocalDateTime.now().plusSeconds(ttlSeconds()));
            a.setOriginalInput(snapshot.getOriginalInput());
            a.setPartialResponse(snapshot.getPartialResponse());
            a.setCompletedTools(toJsonArray(snapshot.getCompletedTools()));
            a.setRound(snapshot.getRound());
            approvalMapper.insert(a);
            snapshot.setPendingConfirmId(a.getConfirmId());
            scheduleExpiry(a.getConfirmId());
            log.info("创建审批记录 [confirmId={}, tool={}, conversationId={}, userId={}]",
                    a.getConfirmId(), a.getToolName(), a.getConversationId(), a.getUserId());
            return a.getConfirmId();
        } catch (Exception e) {
            // best-effort：DB 失败不抛（否则会逃逸到 planAndExecuteAsync 通用兜底 → 硬流失败），
            // 由调用方降级到内存快照继续提示用户
            log.error("创建审批记录失败, tool={}, conversationId={}",
                    snapshot.getPendingToolName(), snapshot.getConversationId(), e);
            return null;
        }
    }

    @Override
    public AgentApproval getByConfirmId(String confirmId, Long userId) {
        return approvalMapper.selectOne(new LambdaQueryWrapper<AgentApproval>()
                .eq(AgentApproval::getConfirmId, confirmId)
                .eq(AgentApproval::getUserId, userId));
    }

    @Override
    public ApprovalDecisionResult markApproved(String confirmId, Long userId) {
        AgentApproval a = getByConfirmId(confirmId, userId);
        if (a == null) {
            return ApprovalDecisionResult.NOT_FOUND;
        }
        if (!AgentApproval.STATUS_PENDING.equals(a.getStatus())) {
            return ApprovalDecisionResult.NOT_PENDING;
        }
        if (a.getExpiredAt() != null && a.getExpiredAt().isBefore(LocalDateTime.now())) {
            markExpired(confirmId);
            return ApprovalDecisionResult.EXPIRED;
        }
        // 原子 CAS：status=pending 且未过期才置 approved（防双击/清扫竞态双执行）
        int rows = approvalMapper.update(null, new LambdaUpdateWrapper<AgentApproval>()
                .eq(AgentApproval::getConfirmId, confirmId)
                .eq(AgentApproval::getUserId, userId)
                .eq(AgentApproval::getStatus, AgentApproval.STATUS_PENDING)
                .ge(AgentApproval::getExpiredAt, LocalDateTime.now())
                .set(AgentApproval::getStatus, AgentApproval.STATUS_APPROVED)
                .set(AgentApproval::getDecidedAt, LocalDateTime.now()));
        if (rows == 1) {
            log.info("审批通过 [confirmId={}, userId={}]", confirmId, userId);
            return ApprovalDecisionResult.APPROVED;
        }
        return ApprovalDecisionResult.NOT_PENDING;
    }

    @Override
    public ApprovalDecisionResult markRejected(String confirmId, Long userId) {
        AgentApproval a = getByConfirmId(confirmId, userId);
        if (a == null) {
            return ApprovalDecisionResult.NOT_FOUND;
        }
        if (!AgentApproval.STATUS_PENDING.equals(a.getStatus())) {
            return ApprovalDecisionResult.NOT_PENDING;
        }
        if (a.getExpiredAt() != null && a.getExpiredAt().isBefore(LocalDateTime.now())) {
            markExpired(confirmId);
            return ApprovalDecisionResult.EXPIRED;
        }
        int rows = approvalMapper.update(null, new LambdaUpdateWrapper<AgentApproval>()
                .eq(AgentApproval::getConfirmId, confirmId)
                .eq(AgentApproval::getUserId, userId)
                .eq(AgentApproval::getStatus, AgentApproval.STATUS_PENDING)
                .set(AgentApproval::getStatus, AgentApproval.STATUS_REJECTED)
                .set(AgentApproval::getDecidedAt, LocalDateTime.now()));
        if (rows == 1) {
            log.info("审批拒绝 [confirmId={}, userId={}]", confirmId, userId);
            return ApprovalDecisionResult.REJECTED;
        }
        return ApprovalDecisionResult.NOT_PENDING;
    }

    @Override
    public boolean markExpired(String confirmId) {
        int rows = approvalMapper.update(null, new LambdaUpdateWrapper<AgentApproval>()
                .eq(AgentApproval::getConfirmId, confirmId)
                .eq(AgentApproval::getStatus, AgentApproval.STATUS_PENDING)
                .set(AgentApproval::getStatus, AgentApproval.STATUS_EXPIRED)
                .set(AgentApproval::getDecidedAt, LocalDateTime.now()));
        return rows > 0;
    }

    @Override
    public boolean markExecuted(String confirmId, Long userId) {
        int rows = approvalMapper.update(null, new LambdaUpdateWrapper<AgentApproval>()
                .eq(AgentApproval::getConfirmId, confirmId)
                .eq(AgentApproval::getUserId, userId)
                .eq(AgentApproval::getStatus, AgentApproval.STATUS_APPROVED)
                .isNull(AgentApproval::getExecutedAt)
                .set(AgentApproval::getExecutedAt, LocalDateTime.now()));
        if (rows > 0) {
            log.info("审批工具已执行 [confirmId={}]", confirmId);
        }
        return rows > 0;
    }

    @Override
    @Scheduled(fixedDelay = 60_000, initialDelay = 30_000)
    public void expireOverdue() {
        try {
            int rows = approvalMapper.update(null, new LambdaUpdateWrapper<AgentApproval>()
                    .eq(AgentApproval::getStatus, AgentApproval.STATUS_PENDING)
                    .lt(AgentApproval::getExpiredAt, LocalDateTime.now())
                    .set(AgentApproval::getStatus, AgentApproval.STATUS_EXPIRED)
                    .set(AgentApproval::getDecidedAt, LocalDateTime.now()));
            if (rows > 0) {
                log.info("审批清扫：{} 条记录过期", rows);
            }
        } catch (Exception e) {
            log.warn("审批清扫失败", e);
        }
    }

    private void scheduleExpiry(String confirmId) {
        try {
            long ttlMillis = ttlSeconds() * 1000L;
            taskScheduler.schedule(() -> markExpired(confirmId),
                    Instant.ofEpochMilli(System.currentTimeMillis() + ttlMillis));
        } catch (Exception e) {
            log.warn("审批过期调度失败, confirmId={}", confirmId, e);
        }
    }

    private long ttlSeconds() {
        long ttl = promptGuardProperties.getApproval().getTtlSeconds();
        return ttl > 0 ? ttl : 300L;
    }

    private static String newConfirmId() {
        return "cfm_" + UUID.randomUUID().toString().replace("-", "").substring(0, CONFIRM_ID_RANDOM_LEN);
    }

    private static String toJsonArray(List<String> list) {
        if (list == null || list.isEmpty()) return "[]";
        try {
            return JSON.writeValueAsString(list);
        } catch (Exception e) {
            return "[]";
        }
    }
}
