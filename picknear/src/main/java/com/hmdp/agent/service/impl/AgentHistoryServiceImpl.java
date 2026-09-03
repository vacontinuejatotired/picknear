package com.hmdp.agent.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.hmdp.agent.dto.ConversationVO;
import com.hmdp.agent.dto.MessageVO;
import com.hmdp.agent.entity.AgentConversation;
import com.hmdp.agent.entity.AgentMessage;
import com.hmdp.agent.history.compression.ConversationTurnRecordedEvent;
import com.hmdp.agent.mapper.AgentConversationMapper;
import com.hmdp.agent.mapper.AgentMessageMapper;
import com.hmdp.agent.service.AgentHistoryService;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 历史会话服务实现
 */
@Slf4j
@Service
public class AgentHistoryServiceImpl implements AgentHistoryService {

    /** 会话标题截断长度（列 varchar(200)，取 100 字留余量） */
    private static final int TITLE_MAX = 100;

    @Resource
    private AgentConversationMapper conversationMapper;

    @Resource
    private AgentMessageMapper messageMapper;

    @Resource
    private ApplicationEventPublisher eventPublisher;

    @Override
    @Transactional
    public void recordTurn(Long userId, String conversationId, String userContent, String assistantContent) {
        if (conversationId == null || conversationId.isBlank() || userId == null) {
            log.warn("recordTurn 参数缺失，跳过 userId={}, conversationId={}", userId, conversationId);
            return;
        }

        // 按 (conversationId, userId) 查询：既做归属隔离（不污染他人会话），
        // 也避免把他人会话误判为己有
        AgentConversation conversation = conversationMapper.selectOne(
                new LambdaQueryWrapper<AgentConversation>()
                        .eq(AgentConversation::getConversationId, conversationId)
                        .eq(AgentConversation::getUserId, userId));

        if (conversation == null) {
            // 首次回合：创建会话，标题 = 首条用户消息截断
            AgentConversation created = new AgentConversation();
            created.setConversationId(conversationId);
            created.setUserId(userId);
            created.setTitle(truncate(userContent, TITLE_MAX));
            created.setStatus(0);
            conversationMapper.insert(created);
        } else {
            // 续传：显式刷新 updated_at（ON UPDATE CURRENT_TIMESTAMP 只在 UPDATE 行时触发，
            // 仅 INSERT 消息不会刷新），保证会话列表按活跃时间正确排序
            conversationMapper.update(null, new LambdaUpdateWrapper<AgentConversation>()
                    .eq(AgentConversation::getConversationId, conversationId)
                    .eq(AgentConversation::getUserId, userId)
                    .set(AgentConversation::getUpdatedAt, LocalDateTime.now()));
        }

        insertMessage(userId, conversationId, "user", userContent);
        insertMessage(userId, conversationId, "assistant", assistantContent);

        // 写后投递：事务提交后发"回合落库事件"，驱动异步压缩（压缩禁用时由 Dispatcher enabled 门拦截，零开销）
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                try {
                    eventPublisher.publishEvent(new ConversationTurnRecordedEvent(conversationId, userId));
                } catch (Exception e) {
                    log.debug("会话压缩事件发布失败（旁路，忽略）conversationId={}", conversationId, e);
                }
            }
        });
    }

    @Override
    public List<ConversationVO> listConversations(Long userId) {
        return conversationMapper.selectConversationList(userId);
    }

    @Override
    public List<MessageVO> listMessages(Long userId, String conversationId) {
        // 归属校验：会话不属于当前用户 → 返回空（不泄露存在性）
        Long owned = conversationMapper.selectCount(
                new LambdaQueryWrapper<AgentConversation>()
                        .eq(AgentConversation::getConversationId, conversationId)
                        .eq(AgentConversation::getUserId, userId));
        if (owned == null || owned == 0) {
            log.warn("listMessages 会话不存在或无权访问 conversationId={}, userId={}", conversationId, userId);
            return List.of();
        }
        return messageMapper.selectList(
                        new LambdaQueryWrapper<AgentMessage>()
                                .eq(AgentMessage::getConversationId, conversationId)
                                .eq(AgentMessage::getUserId, userId)
                                .orderByAsc(AgentMessage::getCreatedAt))
                .stream()
                .map(m -> {
                    MessageVO vo = new MessageVO();
                    vo.setRole(m.getRole());
                    vo.setContent(m.getContent());
                    vo.setCreatedAt(m.getCreatedAt());
                    return vo;
                })
                .toList();
    }

    private void insertMessage(Long userId, String conversationId, String role, String content) {
        AgentMessage message = new AgentMessage();
        message.setConversationId(conversationId);
        message.setUserId(userId);
        message.setRole(role);
        message.setContent(content);
        messageMapper.insert(message);
    }

    private String truncate(String s, int max) {
        if (s == null) {
            return "";
        }
        return s.length() <= max ? s : s.substring(0, max);
    }
}
