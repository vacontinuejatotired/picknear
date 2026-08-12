package com.hmdp.agent.observability.api;

import com.hmdp.agent.observability.model.AgentField;
import io.micrometer.observation.Observation;

/**
 * 空实现（Fail-Open 兜底）：埋点异常时返回，业务主链路不受影响。
 * <p>
 * 单例共享：NoopAgentSpan 无状态、end 幂等、属性丢弃。
 * </p>
 */
public final class NoopAgentSpan implements AgentSpan {

    public static final NoopAgentSpan INSTANCE = new NoopAgentSpan();

    private NoopAgentSpan() {
    }

    @Override
    @Deprecated
    public AgentSpan attribute(String key, String value) {
        return this;
    }

    @Override
    public AgentSpan set(AgentField field, String value) {
        return this;
    }

    @Override
    public AgentSpan set(AgentField field, String segment, String value) {
        return this;
    }

    @Override
    public Observation.Scope openScope() {
        return Observation.Scope.NOOP;
    }

    @Override
    public void closeRootScope() {
        // no-op（Fail-Open：无 scope 可关）
    }

    @Override
    public void end() {
        // no-op
    }

    @Override
    public String spanName() {
        return "agent.noop";
    }
}
