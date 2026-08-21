package com.hmdp.agent.dag.executor;

/**
 * 工具调用器接口
 * 
 * <p>封装工具的执行逻辑，由 HybridToolLoop 构建。</p>
 *
 * @author DAG Planning Executor
 * @version 1.9
 */
@FunctionalInterface
public interface ToolInvoker {
    
    /**
     * 执行工具
     * 
     * @return 工具执行结果
     * @throws Exception 工具执行异常
     */
    Object invoke() throws Exception;
    
    /**
     * 获取工具返回类型（默认 Object）
     * 
     * @return 返回类型
     */
    default Class<?> getReturnType() {
        return Object.class;
    }
}
