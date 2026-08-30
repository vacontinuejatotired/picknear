package com.hmdp.agent.execution.loop;

/**
 * 工具调用器接口
 *
 * <p>封装工具的执行逻辑，由 DagStrategy 构建。</p>
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
