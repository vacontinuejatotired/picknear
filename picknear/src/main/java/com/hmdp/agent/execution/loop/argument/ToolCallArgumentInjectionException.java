package com.hmdp.agent.execution.loop.argument;

/**
 * 工具调用参数注入异常。
 *
 * <p>任何参数组装失败都应抛出可读异常，由执行器按工具失败处理，禁止把错误参数静默传下去。</p>
 */
public class ToolCallArgumentInjectionException extends RuntimeException {

    public ToolCallArgumentInjectionException(String message) {
        super(message);
    }

    public ToolCallArgumentInjectionException(String message, Throwable cause) {
        super(message, cause);
    }
}
