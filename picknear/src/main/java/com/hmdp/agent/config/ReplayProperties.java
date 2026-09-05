package com.hmdp.agent.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 多轮记忆回放配置。
 * <p>
 * 配置项前缀：agent.replay
 * </p>
 */
@Data
@Component
@ConfigurationProperties(prefix = "agent.replay")
public class ReplayProperties {

    /** 多轮记忆回放总开关（false → 不读历史，回归单条行为） */
    private boolean enabled = true;

    /** 回放的最近轮数上限（不含当前轮）；单次读尾部 2×keepRecentTurns 条 user/assistant，硬上限防 token 涨 */
    private int keepRecentTurns = 6;

    /** 回放历史累计字符预算（近似 token），超预算从最旧丢弃、最新一条恒保留；0=不限制。
     *  防止超长 message 把每轮输入 token 撑爆；P2 异步压缩上线后本处退化为纯兜底。 */
    private int maxReplayChars = 3000;
}