package com.hmdp.agent.dag.strategy;

/**
 * 重试策略接口
 * 
 * <p>定义工具执行失败时的重试逻辑。</p>
 *
 * @author DAG Planning Executor
 * @version 1.9
 */
public interface RetryStrategy {
    
    /**
     * 判断是否应该重试
     * 
     * @param e       异常
     * @param attempt 当前尝试次数（从 0 开始）
     * @return true 表示应该重试
     */
    boolean shouldRetry(Exception e, int attempt);
    
    /**
     * 获取重试延迟时间（毫秒）
     * 
     * @param attempt 当前尝试次数（从 0 开始）
     * @return 延迟时间（毫秒）
     */
    long getRetryDelay(int attempt);
}
