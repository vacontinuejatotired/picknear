package com.hmdp.agent.observability.backend.impl;

import com.hmdp.agent.observability.backend.TraceBackend;
import com.hmdp.agent.observability.backend.TraceBackendCapabilities;
import org.springframework.stereotype.Component;

/**
 * 通用 OTLP 直连后端基类（Jaeger / SigNoz / Tempo 等支持 span attributes 展示的后端共用能力）。
 * <p>
 * 具体后端只需继承本类并给 id——新增平台 = 新增子类 + {@link TraceBackendType} 枚举项 + 配置段，
 * 业务代码零改动（OCP）。接入参数不同照样走 yaml（本类不含 endpoint）。
 * </p>
 */
public abstract class GenericOtlpBackend implements TraceBackend {

    private final String id;
    private final TraceBackendCapabilities capabilities;

    protected GenericOtlpBackend(String id, TraceBackendCapabilities capabilities) {
        this.id = id;
        this.capabilities = capabilities;
    }

    @Override
    public String id() {
        return id;
    }

    @Override
    public TraceBackendCapabilities capabilities() {
        return capabilities;
    }

    /** Jaeger 后端（本地/自建，OTLP HTTP 直连，接入参数走 management.otlp.tracing.*） */
    @Component
    public static class JaegerBackend extends GenericOtlpBackend {
        public JaegerBackend() {
            super("jaeger", TraceBackendCapabilities.genericOtlp());
        }
    }

    /** SigNoz 后端（OTLP 直连） */
    @Component
    public static class SignozBackend extends GenericOtlpBackend {
        public SignozBackend() {
            super("signoz", TraceBackendCapabilities.genericOtlp());
        }
    }

    /** 通用 OTel Collector 前置（后续增强形态 A：应用固定发 collector，后端路由下沉 collector 配置） */
    @Component
    public static class CollectorBackend extends GenericOtlpBackend {
        public CollectorBackend() {
            super("collector", TraceBackendCapabilities.genericOtlp());
        }
    }
}