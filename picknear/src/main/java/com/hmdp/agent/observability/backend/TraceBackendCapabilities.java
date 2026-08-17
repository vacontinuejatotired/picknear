package com.hmdp.agent.observability.backend;

import com.hmdp.agent.observability.model.AgentSpanSpec;

import java.util.ArrayList;
import java.util.List;

/**
 * 观测后端能力描述 —— 行为适配层的决策依据（评审 13.2.2 单一事实源）。
 * <p>
 * 字段语义（详见观测后端解耦改造方案 §4.1）：
 * <ul>
 *   <li>{@link #supportsSpanAttributes}：自定义属性在目标后端 UI 是否可见。
 *       <b>唯一事实源</b>——「是否把语义编码进 span 名」由命名策略从它推导
 *       （{@link #defaultSemanticNameEncoding()}），不在本类再设布尔字段，杜绝互生失配。</li>
 *   <li>{@link #contentSupplementRequired}：是否需补发 OTel 标准属性
 *       {@code gen_ai.request/response.content}（默认 true）。</li>
 *   <li>{@link #quotaAware}：是否配额敏感（决定白名单默认严谨度，默认 false）。</li>
 *   <li>{@link #defaultTracePrefixes}：默认放行的 span 前缀；{@code agent.} 项
 *       由 {@link AgentSpanSpec#NAMESPACE} 常量派生（评审 13.3.3，消除多份字面量）。</li>
 * </ul>
 * </p>
 */
public final class TraceBackendCapabilities {

    private final boolean supportsSpanAttributes;
    private final boolean contentSupplementRequired;
    private final boolean quotaAware;
    private final List<String> defaultTracePrefixes;

    private TraceBackendCapabilities(boolean supportsSpanAttributes, boolean contentSupplementRequired,
                                     boolean quotaAware, List<String> defaultTracePrefixes) {
        this.supportsSpanAttributes = supportsSpanAttributes;
        this.contentSupplementRequired = contentSupplementRequired;
        this.quotaAware = quotaAware;
        this.defaultTracePrefixes = List.copyOf(defaultTracePrefixes);
    }

    /** Langfuse（现状对齐）：attributes 不可见 → 语义需编码进 span 名；敏感配额 → 白名单严谨 */
    public static TraceBackendCapabilities langfuse() {
        return new TraceBackendCapabilities(
                false, true, true,
                defaultPrefixes());
    }

    /** 通用 OTLP 后端（Jaeger/SigNoz/Tempo 等）：attributes 可见 → 语义靠属性展示；无配额概念 */
    public static TraceBackendCapabilities genericOtlp() {
        return new TraceBackendCapabilities(
                true, true, false,
                defaultPrefixes());
    }

    /** 本地控制台调试：放大输出，不关心配额 */
    public static TraceBackendCapabilities console() {
        return new TraceBackendCapabilities(
                true, true, false,
                new ArrayList<>(defaultPrefixes()));
    }

    /** Noop：不产生任何导出语义（能力对其无意义，占位即可） */
    public static TraceBackendCapabilities noop() {
        return new TraceBackendCapabilities(
                true, false, false,
                List.of());
    }

    /** 默认放行前缀集合：agent. 由 NAMESPACE 常量派生（单一事实源，评审 13.3.3） */
    private static List<String> defaultPrefixes() {
        return new ArrayList<>(List.of(
                AgentSpanSpec.NAMESPACE,
                "spring.ai.",
                "gen_ai."));
    }

    public boolean supportsSpanAttributes() {
        return supportsSpanAttributes;
    }

    /** 默认的语义命名编码决策（供命名策略消费；用户级覆盖开关 auto/true/false 见配置节） */
    public boolean defaultSemanticNameEncoding() {
        return !supportsSpanAttributes;
    }

    public boolean contentSupplementRequired() {
        return contentSupplementRequired;
    }

    public boolean quotaAware() {
        return quotaAware;
    }

    /** 该后端默认放行的 span 前缀（不可变拷贝） */
    public List<String> defaultTracePrefixes() {
        return defaultTracePrefixes;
    }
}