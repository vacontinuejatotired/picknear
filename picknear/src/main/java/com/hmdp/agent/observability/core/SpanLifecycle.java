package com.hmdp.agent.observability.core;

import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import org.springframework.stereotype.Component;

/**
 * Observation 生命周期管理（架构文档 §3.3 / §6.1 实测 API）。
 * <p>
 * 封装的机制要点（micrometer 1.14.5 实测）：
 * <ul>
 *   <li>{@link Observation#createNotStarted(String, java.util.function.Supplier, ObservationRegistry)}
 *       三参重载——父子关系在【创建时】固化（取当前线程 scope 栈顶为父），与 start 时机无关</li>
 *   <li>子 span 必须在其父 span 的 scope 已 open 的线程内创建（时序契约 T1）</li>
 *   <li>scope 必须 try-with-resources 配对（T2，防线程池串味）</li>
 *   <li>属性任意时机写入 context，onStop 统一同步到 span（DefaultTracingObservationHandler 实测）</li>
 * </ul>
 */
@Component
public class SpanLifecycle {

    private final ObservationRegistry registry;

    public SpanLifecycle(ObservationRegistry registry) {
        this.registry = registry;
    }

    /**
     * 创建并 start 一个 observation（父子关系在创建时自动从当前线程 scope 栈捕获）。
     */
    public Observation start(String name) {
        return Observation.createNotStarted(name, Observation.Context::new, registry)
                .start();
    }

    /** 暴露 registry（诊断/测试用） */
    public ObservationRegistry registry() {
        return registry;
    }

    /**
     * 创建（不 start）——父级固化后由调用方控制 start 时机。
     */
    public Observation create(String name) {
        return Observation.createNotStarted(name, Observation.Context::new, registry);
    }

    /**
     * 结束 observation（幂等保护由上层 AgentSpan 状态位负责）。
     */
    public void stop(Observation observation) {
        observation.stop();
    }

    /**
     * 打开 scope（调用方必须 try-with-resources 配对关闭）。
     */
    public Observation.Scope openScope(Observation observation) {
        return observation.openScope();
    }
}
