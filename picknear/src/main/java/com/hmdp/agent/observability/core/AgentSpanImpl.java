package com.hmdp.agent.observability.core;

import com.hmdp.agent.observability.api.AgentSpan;
import com.hmdp.agent.observability.model.AgentField;
import com.hmdp.agent.observability.support.AttributeSanitizer;
import com.hmdp.agent.observability.support.SanitizeLevel;
import io.micrometer.common.KeyValue;
import io.micrometer.observation.Observation;
import lombok.extern.slf4j.Slf4j;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * AgentSpan 默认实现。
 * <p>
 * 幂等保证（时序契约 T3）：{@link io.micrometer.observation.SimpleObservation#stop()}
 * 无幂等保护，重复 stop 会让 meter handler 重复计数——故用状态位 {@link #ended}
 * 保证只 stop 一次。
 * </p>
 * <p>
 * 属性写入：任意时机写入 Observation context，onStop 时由
 * DefaultTracingObservationHandler 统一同步到 span（micrometer 1.14.5 实测）。
 * 所有值经 {@link AttributeSanitizer} 统一脱敏出口。
 * </p>
 */
@Slf4j
public class AgentSpanImpl implements AgentSpan {

    private final Observation observation;
    private final Observation.Scope scope;
    /** 属主线程：openScope 所在线程（根 scope 需在属主线程关闭，防跨线程泄漏） */
    private final Thread scopeOpener;
    private final AttributeSanitizer sanitizer;
    private final AtomicBoolean ended = new AtomicBoolean(false);
    private final AtomicBoolean scopeClosed = new AtomicBoolean(false);

    public AgentSpanImpl(Observation observation, Observation.Scope scope, AttributeSanitizer sanitizer) {
        this.observation = observation;
        this.scope = scope;
        this.scopeOpener = Thread.currentThread();
        this.sanitizer = sanitizer;
    }

    @Override
    @Deprecated
    public AgentSpan attribute(String key, String value) {
        // 已知 key 走注册表（含其脱敏级别），一次性临时 key 降级摘要脱敏
        AgentField field = AgentField.byKey(key).orElse(null);
        return field != null ? set(field, value) : set(key, SanitizeLevel.SUMMARY, value);
    }

    @Override
    public AgentSpan set(AgentField field, String value) {
        observation.lowCardinalityKeyValue(KeyValue.of(field.key(), field.level().sanitize(sanitizer, value)));
        return this;
    }

    @Override
    public AgentSpan set(AgentField field, String segment, String value) {
        observation.lowCardinalityKeyValue(KeyValue.of(field.key(segment), field.level().sanitize(sanitizer, value)));
        return this;
    }

    /** 内部：未注册 key 的降级写入（attribute 逃生口用） */
    private AgentSpan set(String rawKey, SanitizeLevel level, String value) {
        observation.lowCardinalityKeyValue(KeyValue.of(rawKey, level.sanitize(sanitizer, value)));
        return this;
    }

    @Override
    public Observation.Scope openScope() {
        return observation.openScope();
    }

    @Override
    public void end() {
        if (ended.compareAndSet(false, true)) {
            try {
                observation.stop();
            } catch (Exception e) {
                log.warn("[observability] AgentSpan 结束失败 span={}", spanName(), e);
            } finally {
                closeScopeSafely();
            }
        }
    }

    @Override
    public void closeRootScope() {
        closeScopeSafely();
    }

    /**
     * 幂等关闭 scope：仅属主线程实际 close（异线程 close 会覆写关闭者线程的 current scope，
     * 且开启者线程 ThreadLocal 不会因此被清理）。非属主/重复调用幂等忽略。
     */
    private void closeScopeSafely() {
        if (scopeClosed.compareAndSet(false, true)) {
            if (Thread.currentThread() == scopeOpener) {
                try {
                    scope.close();
                } catch (Exception e) {
                    log.warn("[observability] scope 关闭失败 span={}", spanName(), e);
                }
            }
            // 非属主线程：忽略（scope 由属主线程的 closeRootScope() 负责关闭）
        }
    }

    @Override
    public String spanName() {
        return observation.getContext().getName();
    }
}
