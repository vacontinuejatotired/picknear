package com.hmdp.agent.dag.strategy;

/**
 * 重试策略接口
 *
 * <p>定义工具执行失败时的重试逻辑。</p>
 *
 * @author DAG Planning Executor
 * @version 2.0
 */
public interface RetryStrategy {

    /**
     * 判断是否应该重试（使用全局配置）
     *
     * @param e       异常
     * @param attempt 当前尝试次数（从 0 开始）
     * @return true 表示应该重试
     */
    boolean shouldRetry(Exception e, int attempt);

    /**
     * 判断是否应该重试（基于工具元数据）
     * <p>
     * 优先使用工具级配置（{@code @ToolMeta}），否则使用全局配置。
     * </p>
     *
     * @param e       异常
     * @param attempt 当前尝试次数（从 0 开始）
     * @param toolName 工具名称（可选，null 时使用全局配置）
     * @param isTimeout 是否为超时异常
     * @return true 表示应该重试
     */
    default boolean shouldRetry(Exception e, int attempt, String toolName, boolean isTimeout) {
        return shouldRetry(e, attempt);
    }

    /**
     * 获取重试延迟时间（毫秒）
     *
     * @param attempt 当前尝试次数（从 0 开始）
     * @return 延迟时间（毫秒）
     */
    long getRetryDelay(int attempt);
}
