package com.hmdp.agent.history.compression;

import lombok.RequiredArgsConstructor;

/**
 * 压缩任务值 — 显式持有 (conversationId, userId)，不碰 ThreadLocal；
 * 由压缩线程池执行，经 {@link CompressTaskLockGuard} 包装后互斥。
 */
@RequiredArgsConstructor
public class CompressionTask implements Runnable {

    private final CompressionOrchestrator orchestrator;
    private final String conversationId;
    private final Long userId;

    @Override
    public void run() {
        orchestrator.compressCatchUp(conversationId, userId);
    }
}