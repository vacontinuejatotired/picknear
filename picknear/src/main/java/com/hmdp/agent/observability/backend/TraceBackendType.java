package com.hmdp.agent.observability.backend;

import java.util.Optional;

/**
 * 观测后端类型枚举（合法值清单，供配置校验/文档与装配器降级判定使用）。
 * <p>
 * 对应 {@code hmdp.ai-observability.backend.type} 的合法取值。装配器按 {@link TraceBackend#id()}
 * 匹配，不在本枚举内的取值按 Fail-Open 分级降级为 {@code noop}（见
 * 观测后端解耦改造方案 §4.2）。新增后端 = 新增本枚举项 + 实现类 + 配置段（OCP）。
 * </p>
 */
public enum TraceBackendType {

    /** Langfuse（当前默认后端，兼容现状行为） */
    LANGFUSE("langfuse"),
    /** Jaeger（本地/自建 OTLP 后端，支持 span attributes 展示） */
    JAEGER("jaeger"),
    /** SigNoz（OTLP 后端，支持 span attributes 展示） */
    SIGNOZ("signoz"),
    /** 通用 OTel Collector 前置（后续增强形态 A，应用直连 collector） */
    COLLECTOR("collector"),
    /** 本地控制台/日志调试，无外部依赖 */
    CONSOLE("console"),
    /** 关闭观测（整门面 Noop 空转） */
    NOOP("noop");

    private final String id;

    TraceBackendType(String id) {
        this.id = id;
    }

    public String id() {
        return id;
    }

    /** 字符串 → 枚举；未知值返回 empty（由装配器按其类型处理，不在此抛异常） */
    public static Optional<TraceBackendType> from(String id) {
        if (id == null || id.isBlank()) {
            return Optional.empty();
        }
        for (TraceBackendType type : values()) {
            if (type.id.equals(id)) {
                return Optional.of(type);
            }
        }
        return Optional.empty();
    }
}