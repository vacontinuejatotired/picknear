package com.hmdp.agent.history.compression;

import com.hmdp.agent.config.CompressionExecutorProperties;
import com.hmdp.agent.config.ContextCompressionProperties;
import com.hmdp.agent.history.ConversationMemoryKeyFactory;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RedissonClient;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.concurrent.RejectedExecutionException;

/**
 * 回合落库 → 压缩投递门面。只做：enabled 校验 → 组装「锁守卫(压缩任务)」→ 投递独立压缩池。
 * 队列拒绝（AbortPolicy）时 catch 置 dirty 交 sweeper，不走 CallerRuns（防占用请求收尾线程）。
 */
@Slf4j
@Component
public class ConversationCompressionDispatcher {

    @Resource
    private ContextCompressionProperties properties;

    @Resource
    private CompressionExecutorProperties executorProperties;

    @Resource
    private ConversationMemoryKeyFactory keyFactory;

    @Resource
    private DirtyMarker dirtyMarker;

    @Resource
    private RedissonClient redisson;

    @Resource
    private CompressionOrchestrator orchestrator;

    @Resource(name = "compressExecutor")
    private java.util.concurrent.Executor compressExecutor;

    @EventListener
    public void onTurnRecorded(ConversationTurnRecordedEvent event) {
        schedule(event.conversationId(), event.userId());
    }

    /** 对指定会话投递一次压缩（写回合/sweeper 共用入口）。 */
    public void schedule(String conversationId, Long userId) {
        if (!properties.isEnabled() || userId == null || conversationId == null || conversationId.isBlank()) {
            return;
        }
        Runnable task = new CompressTaskLockGuard(redisson, keyFactory, executorProperties,
                dirtyMarker, conversationId, userId,
                new CompressionTask(orchestrator, conversationId, userId));
        try {
            compressExecutor.execute(task);
        } catch (RejectedExecutionException e) {
            log.warn("压缩队列已满，置 dirty 待 sweeper 自愈 conversationId={}", conversationId);
            dirtyMarker.mark(conversationId, userId);
        }
    }
}