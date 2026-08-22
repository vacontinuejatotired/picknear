package com.hmdp.agent.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * DAG 执行配置
 * 
 * <p>配置项前缀：agent.subtask.dag</p>
 *
 * @author DAG Planning Executor
 * @version 1.9
 */
@Data
@Component
@ConfigurationProperties(prefix = "agent.subtask.dag")
public class DagProperties {
    
    /** 执行器类型：local / distributed */
    private String executor = "local";
    
    /** 层级超时（秒） */
    private int layerTimeoutSeconds = 30;
    
    /** 单工具超时（秒）- 默认值 */
    private int toolTimeoutSeconds = 10;
    
    /** 按工具名配置的超时（优先级高于默认值） */
    private Map<String, Integer> toolTimeouts = Map.of();
    
    /** 压缩策略：truncate / llm */
    private String compressor = "truncate";
    
    private RetryProperties retry = new RetryProperties();
    private TimeoutProperties timeout = new TimeoutProperties();
    
    /**
     * 获取工具超时配置（按工具名优先，否则用默认值）
     */
    public int getToolTimeout(String toolName) {
        return toolTimeouts.getOrDefault(toolName, toolTimeoutSeconds);
    }
    
    /**
     * 重试配置
     */
    @Data
    public static class RetryProperties {
        /** 重试策略：exponential / none */
        private String strategy = "exponential";
        
        /** 是否启用重试 */
        private boolean enabled = true;
        
        /** 最大重试次数 */
        private int maxRetries = 3;
        
        /** 重试基础延迟（毫秒） */
        private long baseDelayMs = 1000;
        
        /** 可重试的异常类名 */
        private List<String> retryableErrors = List.of();
    }
    
    /**
     * 超时配置
     */
    @Data
    public static class TimeoutProperties {
        /** 超时策略：future / none */
        private String strategy = "future";
    }
}
