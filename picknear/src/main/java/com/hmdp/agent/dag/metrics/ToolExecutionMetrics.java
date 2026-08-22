package com.hmdp.agent.dag.metrics;

import lombok.Builder;
import lombok.Data;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 工具执行指标
 * 
 * <p>记录单个工具的执行指标，包括耗时、成功状态等。</p>
 *
 * @author DAG Planning Executor
 * @version 1.9
 */
@Data
@Builder
public class ToolExecutionMetrics {
    
    /** 工具名称 */
    private String toolName;
    
    /** 执行耗时（毫秒） */
    private long duration;
    
    /** 是否成功 */
    private boolean success;
    
    /** 错误信息 */
    private String errorMessage;
    
    /** 层级 */
    private int layer;
    
    /** 执行时间戳 */
    private LocalDateTime executedAt;
    
    /**
     * 指标统计工具类（每次执行独立使用，非单例）
     */
    public static class Stats {
        private static final Logger log = LoggerFactory.getLogger(ToolExecutionMetrics.Stats.class);
        
        /**
         * 计算平均执行时间
         */
        public static double averageDuration(List<ToolExecutionMetrics> metrics) {
            return metrics.stream()
                .mapToLong(ToolExecutionMetrics::getDuration)
                .average()
                .orElse(0.0);
        }
        
        /**
         * 计算成功率
         */
        public static double successRate(List<ToolExecutionMetrics> metrics) {
            if (metrics.isEmpty()) return 1.0;
            long successCount = metrics.stream()
                .filter(ToolExecutionMetrics::isSuccess)
                .count();
            return (double) successCount / metrics.size();
        }
        
        /**
         * 记录指标日志
         */
        public static void logMetrics(List<ToolExecutionMetrics> metrics) {
            metrics.forEach(m -> 
                log.debug("指标: {} - {}ms - {}", 
                    m.getToolName(), m.getDuration(), 
                    m.isSuccess() ? "成功" : "失败")
            );
        }
    }
}
