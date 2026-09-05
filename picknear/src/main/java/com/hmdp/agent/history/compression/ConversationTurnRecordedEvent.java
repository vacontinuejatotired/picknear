package com.hmdp.agent.history.compression;

/**
 * 会话回合落库事件 — 写后投递信号（recordTurn afterCommit 发布，Dispatcher 经 @EventListener 消费）。
 * 事件只作"投递信号"，真正的队列是独立 executors 的 BlockingQueue。
 */
public record ConversationTurnRecordedEvent(String conversationId, Long userId) {
}