package com.hmdp.agent.history;

import com.hmdp.agent.model.Mem;

import java.util.Optional;

/**
 * 会话记忆视图存储 — 端口（内存/Redis/DB 可替换实现）。
 * <p>
 * 仅负责"摘要 + 游标"派生视图的原子读写，不含任何压缩逻辑（压缩在 compression 层）。
 * </p>
 */
public interface ConversationMemoryStore {

    /** 读取会话记忆视图；不存在返回空（调用方退化为纯近期完整窗口）。 */
    Optional<Mem> read(String conversationId, Long userId);

    /** 原子写入新记忆视图（含滑动续期）；按会话互斥要求，调用方应持锁。 */
    void update(String conversationId, Long userId, Mem mem);

    /** 删除会话记忆视图（归档/清扫）。 */
    void delete(String conversationId);
}