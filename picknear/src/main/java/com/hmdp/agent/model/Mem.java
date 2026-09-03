package com.hmdp.agent.model;

/**
 * 会话记忆视图 — 摘要 + 压缩游标（单 key 原子写的最小单元）。
 * <p>
 * {@code summary} = 已压最旧轮次的运行摘要；{@code uptoId} = 已进入摘要的最后一条
 * {@code agent_message.id}；读取永远拿到"旧版或新版"成对快照，杜绝"游标前进但摘要未写"
 * 的撕裂。被压旧轮次原值仍在 {@code agent_message} 永不删除。
 * </p>
 */
public record Mem(String summary, long uptoId, int version, String updatedAt) {

    /** 空记忆视图（会话尚未压缩） */
    public static Mem empty() {
        return new Mem("", 0L, 0, null);
    }

    public boolean hasSummary() {
        return summary != null && !summary.isBlank();
    }
}