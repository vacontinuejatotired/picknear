package com.hmdp.agent.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 工具守卫的配置属性
 * <p>
 * 对应 application.yml 中的 {@code hmdp.prompt-guard.*} 配置项，
 * 修改配置文件即可调整策略规则，无需改动代码。
 * </p>
 * <pre>{@code
 * hmdp:
 *   prompt-guard:
 *     block-tools:
 *       - deleteBlog
 *       - deleteUser
 *     confirm-tools:
 *       - publishTestBlog
 *     block-patterns:
 *       - toolName: ".*[Dd]elete.*"
 *     confirm-patterns:
 *       - arguments: ".*confirm.*"
 *     rate-limit:
 *       max-per-session: 30
 *       window-seconds: 60
 * }</pre>
 */
@Component
@ConfigurationProperties(prefix = "hmdp.prompt-guard")
public class PromptGuardProperties {

    /** 精确匹配 — 直接拦截的工具名列表 */
    private List<String> blockTools = new ArrayList<>();

    /** 精确匹配 — 需用户确认的工具名列表 */
    private List<String> confirmTools = new ArrayList<>();

    /** 正则匹配 — 命中即拦截 */
    private List<PatternRule> blockPatterns = new ArrayList<>();

    /** 正则匹配 — 命中需确认 */
    private List<PatternRule> confirmPatterns = new ArrayList<>();

    /** 频率限制 */
    private RateLimit rateLimit = new RateLimit();

    // ---- getters & setters ----

    public List<String> getBlockTools() { return blockTools; }
    public void setBlockTools(List<String> blockTools) { this.blockTools = blockTools; }

    public List<String> getConfirmTools() { return confirmTools; }
    public void setConfirmTools(List<String> confirmTools) { this.confirmTools = confirmTools; }

    public List<PatternRule> getBlockPatterns() { return blockPatterns; }
    public void setBlockPatterns(List<PatternRule> blockPatterns) { this.blockPatterns = blockPatterns; }

    public List<PatternRule> getConfirmPatterns() { return confirmPatterns; }
    public void setConfirmPatterns(List<PatternRule> confirmPatterns) { this.confirmPatterns = confirmPatterns; }

    public RateLimit getRateLimit() { return rateLimit; }
    public void setRateLimit(RateLimit rateLimit) { this.rateLimit = rateLimit; }

    // ---- 内部类型 ----

    /**
     * 正则匹配规则
     */
    public static class PatternRule {
        /** 匹配工具名称（可选） */
        private String toolName;
        /** 匹配工具参数（可选） */
        private String arguments;

        public String getToolName() { return toolName; }
        public void setToolName(String toolName) { this.toolName = toolName; }
        public String getArguments() { return arguments; }
        public void setArguments(String arguments) { this.arguments = arguments; }
    }

    /**
     * 频率限制配置
     */
    public static class RateLimit {
        /** 每个会话最大工具调用次数 */
        private int maxPerSession = 30;
        /** 统计窗口（秒） */
        private int windowSeconds = 60;

        public int getMaxPerSession() { return maxPerSession; }
        public void setMaxPerSession(int maxPerSession) { this.maxPerSession = maxPerSession; }
        public int getWindowSeconds() { return windowSeconds; }
        public void setWindowSeconds(int windowSeconds) { this.windowSeconds = windowSeconds; }
    }
}
