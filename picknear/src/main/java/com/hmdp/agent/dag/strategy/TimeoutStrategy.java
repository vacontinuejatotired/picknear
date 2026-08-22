package com.hmdp.agent.dag.strategy;

import java.util.function.Supplier;

/**
 * 超时策略接口
 * 
 * <p>定义工具执行的超时逻辑。</p>
 *
 * @author DAG Planning Executor
 * @version 1.9
 */
public interface TimeoutStrategy {
    
    /**
     * 带超时执行任务
     * 
     * @param task      要执行的任务
     * @param timeoutMs 超时时间（毫秒）
     * @param <T>       返回类型
     * @return 任务执行结果
     * @throws Exception 任务执行异常或超时异常
     */
    <T> T executeWithTimeout(Supplier<T> task, long timeoutMs) throws Exception;
}
