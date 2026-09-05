package com.hmdp.agent.history;

import org.springframework.stereotype.Component;

/**
 * 会话记忆相关 Redis key 生成 — 单一职责收敛 key 拼接。
 */
@Component
public class ConversationMemoryKeyFactory {

    private static final String PREFIX = "agent:conv:";

    /** 记忆视图 key：String，Mem JSON，滑动续期 TTL */
    public String memKey(String conversationId) {
        return PREFIX + conversationId + ":mem";
    }

    /** 压缩待补标记 key（事件投递被拒 / worker 失败置位，sweeper 定向自愈） */
    public String dirtyKey(String conversationId) {
        return PREFIX + conversationId + ":dirty";
    }

    /** 活跃会话索引 set key（每次写回合刷新，供 sweeper 定向清扫） */
    public String activeKey() {
        return PREFIX + "active";
    }

    /** 会话压缩互斥锁 key（Redisson RLock） */
    public String lockKey(String conversationId) {
        return "redis:agent:conv:" + conversationId + ":lock";
    }
}