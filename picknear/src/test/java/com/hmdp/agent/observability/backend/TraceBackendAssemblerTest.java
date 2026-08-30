package com.hmdp.agent.observability.backend;

import com.hmdp.agent.observability.backend.impl.ConsoleBackend;
import com.hmdp.agent.observability.backend.impl.GenericOtlpBackend;
import com.hmdp.agent.observability.backend.impl.LangfuseBackend;
import com.hmdp.agent.observability.backend.impl.NoopBackend;
import com.hmdp.agent.observability.model.AgentSpanSpec;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 观测后端装配器单元测试（无 Spring 上下文）。
 * <p>
 * 守护 Fail-Open 分级（观测后端解耦改造方案 §4.2）：type 缺省 → langfuse；显式 noop /
 * 未知值 / 实现缺失 → NoopBackend（不抛异常）；加上 Capabilities 单一事实源推导与前缀派生
 * （评审 13.2.2 / 13.3.3 的回归防线）。
 * </p>
 */
class TraceBackendAssemblerTest {

    /** 模拟容器收集的全部后端实现（与 @Component 注册集合一致，无 Noop） */
    private List<TraceBackend> registeredBackends() {
        return List.of(
                new LangfuseBackend(),
                new GenericOtlpBackend.JaegerBackend(),
                new GenericOtlpBackend.SignozBackend(),
                new GenericOtlpBackend.CollectorBackend(),
                new ConsoleBackend());
    }

    private TraceBackendAssembler assembler(String type) {
        BackendProperties props = new BackendProperties();
        props.setType(type);
        return new TraceBackendAssembler(registeredBackends(), props);
    }

    @Test
    void typeLangfuse_shouldAssembleLangfuseBackend() {
        assertThat(assembler("langfuse").assemble()).isInstanceOf(LangfuseBackend.class);
    }

    @Test
    void typeJaeger_shouldAssembleJaegerBackend() {
        assertThat(assembler("jaeger").assemble()).isInstanceOf(GenericOtlpBackend.JaegerBackend.class);
    }

    @Test
    void typeSignoz_shouldAssembleSignozBackend() {
        assertThat(assembler("signoz").assemble()).isInstanceOf(GenericOtlpBackend.SignozBackend.class);
    }

    @Test
    void typeCollector_shouldAssembleCollectorBackend() {
        assertThat(assembler("collector").assemble()).isInstanceOf(GenericOtlpBackend.CollectorBackend.class);
    }

    @Test
    void typeConsole_shouldAssembleConsoleBackend() {
        assertThat(assembler("console").assemble()).isInstanceOf(ConsoleBackend.class);
    }

    @Test
    void typeBlank_shouldFallbackToLangfuse_defaultCompatibility() {
        // Fail-Open 分级：type 缺省/空 → langfuse（兼容现状，回归基线一致）
        assertThat(assembler(null).assemble()).isInstanceOf(LangfuseBackend.class);
        assertThat(assembler("  ").assemble()).isInstanceOf(LangfuseBackend.class);
    }

    @Test
    void typeNoop_shouldResolveNoopBackendInstance() {
        assertThat(assembler("noop").assemble()).isSameAs(NoopBackend.INSTANCE);
    }

    @Test
    void unknownType_shouldFailOpenToNoop_withoutThrowing() {
        TraceBackend backend = assembler("not-a-real-backend").assemble();
        assertThat(backend).isSameAs(NoopBackend.INSTANCE);
    }

    @Test
    void missingImplementation_shouldFailOpenToNoop() {
        // 模拟容器只注册了一个实现：请求另一个已声明枚举但未注册的后端
        BackendProperties props = new BackendProperties();
        props.setType("jaeger");
        TraceBackendAssembler partial = new TraceBackendAssembler(
                List.of(new LangfuseBackend()), props);
        assertThat(partial.assemble()).isSameAs(NoopBackend.INSTANCE);
    }

    @Test
    void langfuseCapabilities_shouldDeriveSemanticEncodingOn() {
        // 语义命名编码由 supportsSpanAttributes 推导（评审 13.2.2，单一事实源）
        TraceBackendCapabilities caps = new LangfuseBackend().capabilities();
        assertThat(caps.supportsSpanAttributes()).isFalse();
        assertThat(caps.defaultSemanticNameEncoding()).isTrue();
        assertThat(caps.contentSupplementRequired()).isTrue();
        assertThat(caps.quotaAware()).isTrue();
    }

    @Test
    void genericOtlpCapabilities_shouldDeriveSemanticEncodingOff() {
        TraceBackendCapabilities caps = new GenericOtlpBackend.JaegerBackend().capabilities();
        assertThat(caps.supportsSpanAttributes()).isTrue();
        assertThat(caps.defaultSemanticNameEncoding()).isFalse();
        assertThat(caps.contentSupplementRequired()).isTrue();
        assertThat(caps.quotaAware()).isFalse();
    }

    @Test
    void defaultTracePrefixes_shouldDeriveAgentNamespace_fromAgentSpanSpec() {
        // 评审 13.3.3：agent. 项由 AgentSpanSpec.NAMESPACE 常量派生，不写死字面量
        TraceBackendCapabilities caps = new LangfuseBackend().capabilities();
        assertThat(caps.defaultTracePrefixes())
                .contains(AgentSpanSpec.NAMESPACE, "spring.ai.", "gen_ai.");
        assertThat(AgentSpanSpec.NAMESPACE).isEqualTo("agent.");
    }

    @Test
    void langfuseBackend_shouldExposeAssociationAttributesMvp() {
        List<TraceBackend.RootAttributeMapping> mappings = new LangfuseBackend().associationAttributes();
        assertThat(mappings).hasSize(2);
        assertThat(mappings).extracting(TraceBackend.RootAttributeMapping::platformKey)
                .containsExactly("langfuse.user.id", "langfuse.session.id");
        // 评审 13.2.4：值复用现有 AgentField（USER_ID / CONVERSATION_ID）
        assertThat(mappings).extracting(TraceBackend.RootAttributeMapping::sourceField)
                .containsExactlyInAnyOrder(
                        com.hmdp.agent.observability.model.AgentField.USER_ID,
                        com.hmdp.agent.observability.model.AgentField.CONVERSATION_ID);
    }

    @Test
    void traceBackendType_shouldParseKnownValues_andRejectUnknown() {
        assertThat(TraceBackendType.from("langfuse")).contains(TraceBackendType.LANGFUSE);
        assertThat(TraceBackendType.from("jaeger")).contains(TraceBackendType.JAEGER);
        assertThat(TraceBackendType.from("noop")).contains(TraceBackendType.NOOP);
        assertThat(TraceBackendType.from("nope")).isEmpty();
        assertThat(TraceBackendType.from("")).isEmpty();
        assertThat(TraceBackendType.from(null)).isEmpty();
    }

    @Test
    void everyRegisteredBackend_shouldHaveUniqueNonBlankId() {
        List<TraceBackend> backends = registeredBackends();
        long distinct = backends.stream().map(TraceBackend::id).distinct().count();
        assertThat(distinct).as("后端 id 必须唯一").isEqualTo(backends.size());
        assertThat(backends).allSatisfy(b -> assertThat(b.id()).isNotBlank());
    }

    @Test
    void unknownType_shouldStillResolveToValidBackend_neverNull() {
        // 兜底断言：任何 type 输入都不返回 null（调用方无需判空）
        for (String type : List.of("langfuse", "noop", "garbage", "", "java")) {
            Optional.ofNullable(assembler(type).assemble())
                    .orElseThrow(() -> new AssertionError("type=" + type + " 不应返回 null"));
        }
    }
}