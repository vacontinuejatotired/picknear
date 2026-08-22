package com.hmdp.agent.dag.executor;

import com.hmdp.agent.dag.plan.ExecutionPlan;

import java.util.Map;

/**
 * DAG 执行器接口
 * 
 * <p>负责执行 DAG 计划，按层并行/串行执行工具。</p>
 *
 * @author DAG Planning Executor
 * @version 1.9
 */
public interface PlanExecutor {
    
    /**
     * 执行 DAG 计划
     * 
     * @param plan  执行计划
     * @param tools 工具调用器映射
     * @return 执行结果
     */
    DagExecutionResult execute(ExecutionPlan plan, Map<String, ToolInvoker> tools);
}
