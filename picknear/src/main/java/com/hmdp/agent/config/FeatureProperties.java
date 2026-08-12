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

    /** 规划工具路由开关（true=意图→工具组两级路由 TreePlanRouter；false=legacy 紧凑目录+UNCERTAIN 全量重跑） */
    private ToolRouting toolRouting = new ToolRouting();

    @Data
    public static class SubAgent {
        /** true=使用 SubTaskAgent；false=走原 TaskExecutor 串行直调 */
        private boolean enabled = true;
    }

    @Data
    public static class ToolRouting {
        /** true=两级路由（意图树，默认）；false=legacy（紧凑目录+__UNCERTAIN__全量重跑，与现状零行为差异） */
        private boolean enabled = true;
        /** 紧凑标签最大字符数，超出截断加 … */
        private int maxTagLength = 60;
    }
}
