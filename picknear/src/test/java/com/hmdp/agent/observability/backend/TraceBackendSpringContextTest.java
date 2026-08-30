package com.hmdp.agent.observability.backend;

import com.hmdp.agent.observability.backend.impl.ConsoleBackend;
import com.hmdp.agent.observability.backend.impl.GenericOtlpBackend;
import com.hmdp.agent.observability.backend.impl.LangfuseBackend;
import com.hmdp.agent.observability.backend.impl.NoopBackend;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.FilterType;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 观测后端 Spring 容器装配测试（轻量上下文，不依赖 DB/Redis 等外部服务）。
 * <p>
 * 守护 S2 目标（观测后端解耦改造方案 §9）：
 * <ul>
 *   <li>backend 各实现以 {@code @Component} 进容器、无装配冲突（唯一装配入口 = 装配器）</li>
 *   <li>无配置（type 缺省）→ 命中 Langfuse（兼容现状，行为与改造前一致）</li>
 *   <li>{@code hmdp.ai-observability.backend.type} 可驱动装配切换（jaeger 等）</li>
 * </ul>
 * </p>
 */
class TraceBackendSpringContextTest {

    @Configuration
    @EnableConfigurationProperties(BackendProperties.class)
    @ComponentScan(basePackageClasses = TraceBackend.class,
            excludeFilters = @ComponentScan.Filter(type = FilterType.ASSIGNABLE_TYPE, classes = BackendProperties.class))
    static class BackendScanConfig {
    }

    private ApplicationContextRunner context(String... properties) {
        return new ApplicationContextRunner()
                .withUserConfiguration(BackendScanConfig.class)
                .withPropertyValues(properties);
    }

    @Test
    void container_shouldCollectAllRegisteredBackends_withoutNoop() {
        context().run(ctx -> {
            Map<String, TraceBackend> backends = ctx.getBeansOfType(TraceBackend.class);
            // Langfuse + Console + GenericOtlp 内嵌三子类（Jaeger/Signoz/Collector）；Noop 走单例不入容器
            assertThat(backends.values())
                    .hasSize(5)
                    .extracting(TraceBackend::id)
                    .containsExactlyInAnyOrder("langfuse", "console", "jaeger", "signoz", "collector");
            // 装配器是唯一装配入口：容器中存在且可注入
            assertThat(ctx).hasSingleBean(TraceBackendAssembler.class);
        });
    }

    @Test
    void noProperty_shouldAssembleLangfuse_defaultCompatibility() {
        // 现状 yaml 无 hmdp.ai-observability.backend.type → 缺省 langfuse（回归基线一致）
        context().run(ctx -> {
            TraceBackend backend = ctx.getBean(TraceBackendAssembler.class).assemble();
            assertThat(backend).isInstanceOf(LangfuseBackend.class);
            assertThat(ctx.getBean(BackendProperties.class).getType()).isEqualTo("langfuse");
        });
    }

    @Test
    void blankTypeProperty_shouldAssembleLangfuse_too() {
        context("hmdp.ai-observability.backend.type=").run(ctx -> {
            TraceBackend backend = ctx.getBean(TraceBackendAssembler.class).assemble();
            assertThat(backend).isInstanceOf(LangfuseBackend.class);
        });
    }

    @Test
    void jaegerTypeProperty_shouldAssembleJaegerBackend() {
        context("hmdp.ai-observability.backend.type=jaeger").run(ctx -> {
            TraceBackend backend = ctx.getBean(TraceBackendAssembler.class).assemble();
            assertThat(backend).isInstanceOf(GenericOtlpBackend.JaegerBackend.class);
        });
    }

    @Test
    void consoleTypeProperty_shouldAssembleConsoleBackend() {
        context("hmdp.ai-observability.backend.type=console").run(ctx -> {
            TraceBackend backend = ctx.getBean(TraceBackendAssembler.class).assemble();
            assertThat(backend).isInstanceOf(ConsoleBackend.class);
        });
    }

    @Test
    void unknownTypeProperty_shouldFailOpenToNoop_inSpringContext() {
        context("hmdp.ai-observability.backend.type=whatever").run(ctx -> {
            TraceBackend backend = ctx.getBean(TraceBackendAssembler.class).assemble();
            assertThat(backend).isSameAs(NoopBackend.INSTANCE);
        });
    }

    @Test
    void assemblerBean_shouldBeUsableByConsumers_viaConstructorInjection() {
        context().run(ctx -> {
            TraceBackend backend = ctx.getBean(TraceBackendAssembler.class).assemble();
            // 断言装配结果稳定：来回取都是同一实例（Spring 单例 bean）
            assertThat(ctx.getBean(TraceBackendAssembler.class).assemble()).isSameAs(backend);
        });
    }
}