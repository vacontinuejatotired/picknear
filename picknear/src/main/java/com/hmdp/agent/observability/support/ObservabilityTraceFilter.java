package com.hmdp.agent.observability.support;

import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationPredicate;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 观测白名单过滤器（全局）。
 * <p>
 * 背景：Boot 3.4 的 task observation 会把 {@code @Scheduled}/{@code @Async} 任务
 * （如 BatchLoadCache 每 2s、CacheMonitor 每 30s）全部自动上云，单月可消耗
 * 约 21.6 万 units，远超 Langfuse Hobby 的 50k 配额。这里在源头过滤：
 * 只放行观测相关 span（业务 agent.* + LLM spring.ai./gen_ai.*），其余丢弃。
 * </p>
 * <p>
 * 实现选择：{@link ObservationPredicate}（而非 micrometer 1.14 的 ObservationFilter）——
 * predicate 语义明确（返回 false = 该 observation 不创建，不通知任何 handler），
 * Boot 3.4 自动收集容器内所有 ObservationPredicate bean（ObservationRegistryPostProcessor），
 * 无需手动注册；Boot 自身基于配置的过滤（PropertiesObservationFilterPredicate）也是此机制。
 * </p>
 * <p>
 * 扩展：新增观测类型时，在 {@code hmdp.ai-observability.trace-filter.include-prefixes}
 * 追加前缀即可（默认内置 agent. / spring.ai. / gen_ai.，配置为追加而非覆盖）。
 * </p>
 */
@Component
public class ObservabilityTraceFilter implements ObservationPredicate {

    /** 默认放行前缀（业务语义 + LLM 层） */
    private static final List<String> DEFAULT_INCLUDE_PREFIXES =
            List.of("agent.", "spring.ai.", "gen_ai.");

    private final List<String> includePrefixes;

    public ObservabilityTraceFilter(TraceProperties properties) {
        List<String> prefixes = new ArrayList<>(DEFAULT_INCLUDE_PREFIXES);
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
