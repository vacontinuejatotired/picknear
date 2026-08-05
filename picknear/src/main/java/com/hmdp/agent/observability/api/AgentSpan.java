package com.hmdp.agent.observability.api;

import io.micrometer.observation.Observation;

/**
 * 业务 span 句柄（埋点方持有，AutoCloseable）。
 * <p>
 * 使用惯例：{@code try (AgentSpan span = tracer.start(...)) { ...业务...; span.attribute(...); }}
 * 属性可在运行期任意时机写入（内部写入 Observation context，onStop 时统一同步到 span）。
 * </p>
 */
public interface AgentSpan extends AutoCloseable {

    /**
     * 设置属性（运行期任意时机，end 时统一进 span）。值经统一脱敏出口
     * {@code AttributeSanitizer}（脱敏 + 摘要类截断 200 字符）。
     *
     * @return this（链式）
     */
    AgentSpan attribute(String key, String value);

    /**
     * 设置诊断类属性（脱敏 + 4KB 上限，如 plan_json、Guard 投票明细）。
     */
    AgentSpan attributeDiagnostic(String key, String value);

    /**
     * 便捷：设置状态类属性（如 tool.status=OK/FAILED）。
     */
    AgentSpan status(String status);

    /**
     * 打开根 span 的作用域（异步线程入口使用），返回的 Scope 必须 try-with-resources 配对。
     */
    Observation.Scope openScope();

    /**
     * 属主线程显式关闭根 scope（跨线程泄漏防护，断链修复 2026-08-04）。
     * <p>
     * 根 span 的 scope 在请求线程（属主）打开；若 end() 在异线程执行，请求线程的
     * ThreadLocal 永不清理，池化线程复用会导致跨会话 trace 污染。同步段结束时在
     * 属主线程调用本方法关闭；异步段靠 {@link #openScope()} 重新挂载，不依赖原 scope。
     * 幂等：仅属主线程实际 close，非属主/重复调用忽略。
     * </p>
     */
    void closeRootScope();

    /**
     * 结束 span（幂等：重复调用只执行一次 stop）。
     */
    void end();

    /** span 全名（如 agent.tool_call.queryShop） */
    String spanName();

    @Override
    default void close() {
        end();
    }
}
