package com.hmdp.agent.observability.backend.impl;

import com.hmdp.agent.observability.backend.TraceBackend;
import com.hmdp.agent.observability.backend.TraceBackendCapabilities;

/**
 * Noop 后端：关闭观测（等价 {@code trace-enabled: false} 的语义面；装配器降级目标）。
 * <p>
 * 无状态单例（不注册容器 bean，装配器直接引用 {@link #INSTANCE}）：Fail-Open 分级中
 * 「未知 type / 实现缺失 / 装配异常」与显式 {@code type=noop} 时由装配器返回本实例，
 * 不产生任何导出语义（观测后端解耦改造方案 §4.2）。
 * </p>
 */
public class NoopBackend implements TraceBackend {

    public static final NoopBackend INSTANCE = new NoopBackend();

    private NoopBackend() {
    }

    @Override
    public String id() {
        return "noop";
    }

    @Override
    public TraceBackendCapabilities capabilities() {
        return TraceBackendCapabilities.noop();
    }
}