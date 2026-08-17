package com.hmdp.agent.observability.support;

import com.hmdp.agent.observability.backend.TraceBackendAssembler;
import com.hmdp.agent.observability.backend.TraceBackendCapabilities;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationPredicate;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 观测白名单过滤器（全局）。
 * <p>
 * 背景：Boot 3.4 的 task observation 会把 {@code @Scheduled}/{@code @Async} 任务
 * （如 BatchLoadCache 每 2s、CacheMonitor 每 30s）全部自动上云，单月可消耗约 21.6 万
 * units，远超观测后端配额。这里在源头过滤：只放行观测相关 span，其余丢弃。
 * </p>
 * <p>
 * <b>能力化（2026-08-17，观测后端解耦方案 S4）</b>：默认放行前缀由观测后端能力提供
 * （{@link TraceBackendCapabilities#defaultTracePrefixes()}），其中 {@code agent.} 项由
 * {@code AgentSpanSpec.NAMESPACE} 常量派生（评审 13.3.3，消除三处字面量事实源）。
 * 合并语义 = <b>backend 能力默认前缀 + 用户追加</b>，用户不可删除 backend 默认项
 * （评审 13.4-2，防服务端配置误关导致数据全丢）。配额敏感后端（Langfuse）默认严谨；
 * {@code noop} 后端默认前缀为空（配合整门面空转）。
 * </p>
 * <p>
 * 实现选择：{@link ObservationPredicate}（而非 micrometer 1.14 的 ObservationFilter）——
 * predicate 语义明确（返回 false = 该 observation 不创建，不通知任何 handler），
 * Boot 3.4 自动收集容器内所有 ObservationPredicate bean（ObservationRegistryPostProcessor），
 * 无需手动注册；Boot 自身基于配置的过滤（PropertiesObservationFilterPredicate）也是此机制。
 * </p>
 */
@Component
public class ObservabilityTraceFilter implements ObservationPredicate {

    private final List<String> includePrefixes;

    public ObservabilityTraceFilter(TraceProperties properties, TraceBackendAssembler backendAssembler) {
        // 默认前缀：backend 能力（平台中立；agent. 由 NAMESPACE 派生，见 TraceBackendCapabilities）
        List<String> prefixes = new ArrayList<>(backendAssembler.assemble()
                .capabilities()
                .defaultTracePrefixes());
        // 用户追加（不覆盖默认项）：新增观测类型在此扩展
        if (properties.getTraceFilter().getIncludePrefixes() != null) {
            properties.getTraceFilter().getIncludePrefixes().stream()
                    .map(String::trim)
                    .filter(s -> !s.isEmpty())
                    .forEach(prefixes::add);
        }
        this.includePrefixes = prefixes;
    }

    @Override
    public boolean test(String name, Observation.Context context) {
        for (String prefix : includePrefixes) {
            if (name.startsWith(prefix)) {
                return true;
            }
        }
        return false; // 白名单外：不创建 observation，零导出零配额
    }
}