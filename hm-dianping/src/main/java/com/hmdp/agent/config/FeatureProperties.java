package com.hmdp.agent.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 功能开关配置。
 * <p>
 * 配置项前缀：feature
 * </p>
 */
@Data
@Component
@ConfigurationProperties(prefix = "feature")
public class FeatureProperties {

    /** 子 Agent 功能开关 */
    private SubAgent subagent = new SubAgent();

    @Data
    public static class SubAgent {
        /** true=使用 SubTaskAgent；false=走原 TaskExecutor 串行直调 */
        private boolean enabled = true;
    }
}
