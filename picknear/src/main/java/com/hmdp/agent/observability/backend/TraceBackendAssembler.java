package com.hmdp.agent.observability.backend;

import com.hmdp.agent.observability.backend.impl.NoopBackend;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 观测后端装配器（工厂模式收敛为「Spring 收集 + 单装配入口」，评审 13.2.3）。
 * <p>
 * 各 {@link TraceBackend} 实现以 {@code @Component} 注册（NoopBackend 除外，走单例常量），
 * 本类注入 {@code List<TraceBackend>} 按 {@link TraceBackend#id()} 建索引，按配置选择。
 * 新增后端 = 新增实现类 + {@link TraceBackendType} 枚举项 + 配置段，<b>无需改本类</b>（OCP）。
 * </p>
 * <p>
 * Fail-Open 分级（观测后端解耦改造方案 §4.2 / 评审 13.4-1）：
 * <ul>
 *   <li>{@code type} 缺省/空 → langfuse（兼容现状默认，回归基线一致）</li>
 *   <li>{@code type=noop} → {@link NoopBackend#INSTANCE}（显式关闭，整门面空转）</li>
 *   <li>{@code type} 未知值/实现缺失/装配异常 → 降级 {@link NoopBackend#INSTANCE}（WARN 日志，Fail-Open 不阻塞主链路）</li>
 *   <li>{@code type} 合法但接入参数缺失（如 langfuse 未配 base-url）→ <b>不降级</b>：由后续 AgentTracer
 *       埋点照常、OTLP 导出失败告警（现状路径），本装配器只负责选型</li>
 * </ul>
 * </p>
 */
@Slf4j
@Component
public class TraceBackendAssembler {

    /** 缺省后端类型（兼容现状：旧配置不声明 backend.type 时行为不变） */
    public static final String DEFAULT_TYPE = TraceBackendType.LANGFUSE.id();

    private final Map<String, TraceBackend> backendsById;
    private final BackendProperties props;

    public TraceBackendAssembler(List<TraceBackend> backends, BackendProperties props) {
        this.backendsById = backends.stream()
                .collect(Collectors.toUnmodifiableMap(TraceBackend::id, Function.identity(), (a, b) -> a));
        this.props = props;
    }

    /** 按配置解析当前生效的观测后端；任何无法解析的情形都 Fail-Open 到 Noop（不抛异常） */
    public TraceBackend assemble() {
        String type = props.getType();
        if (type == null || type.isBlank()) {
            type = DEFAULT_TYPE;
        }
        TraceBackend backend = backendsById.get(type);
        if (backend != null) {
            return backend;
        }
        if (DEFAULT_TYPE.equals(type)) {
            // 理论上不可达：装配前已按缺省解析；防御性兜底
            log.warn("[observability] langfuse 后端未在容器中注册，降级 NoopBackend");
            return NoopBackend.INSTANCE;
        }
        if (TraceBackendType.NOOP.id().equals(type)) {
            log.info("[observability] 观测后端 type=noop，观测关闭（整门面 Noop 空转）");
            return NoopBackend.INSTANCE;
        }
        log.warn("[observability] 未知观测后端 type={}，降级 NoopBackend（Fail-Open）", type);
        return NoopBackend.INSTANCE;
    }
}