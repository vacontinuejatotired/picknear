package com.hmdp.agent.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * 会话上下文压缩触发与摘要配置。
 * <p>
 * 配置项前缀：agent.context-compression
 * </p>
 */
@Data
@Component
@ConfigurationProperties(prefix = "agent.context-compression")
public class ContextCompressionProperties {

    /** 对话级异步压缩总开关（关 = 回放退化为 P1 纯近期完整窗口，无摘要） */
    private boolean enabled = true;

    /** 每批压缩的轮数（batch-turns，按最旧未压批次切） */
    private int batchTurns = 6;

    /** 触发阈值 */
    private Trigger trigger = new Trigger();

    /** 摘要生成与保真 */
    private Summary summary = new Summary();

    /** Redis 记忆视图 */
    private Redis redis = new Redis();

    @Data
    public static class Trigger {
        /** 未摘要消息估算 token 超此阈值触发压缩 */
        private int tokenThreshold = 3000;
        /** 未摘要消息条数下限（防单条超长漏检；消息越界即触发） */
        private int messageCount = 20;
    }

    @Data
    public static class Summary {
        /** 摘要输出上限（token 估算），单批摘要不超此值 */
        private int maxTokens = 1200;
        /** 摘要是否保留"关键数据点"清单（保真断言/回填用） */
        private boolean keepKeydata = true;
    }

    @Data
    public static class Redis {
        /** 记忆视图 key TTL（读/写滑动续期） */
        private Duration memTtl = Duration.ofDays(7);
    }
}