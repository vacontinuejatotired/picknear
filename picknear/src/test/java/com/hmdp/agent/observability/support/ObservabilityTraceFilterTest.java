package com.hmdp.agent.observability.support;

import com.hmdp.agent.observability.backend.BackendProperties;
import com.hmdp.agent.observability.backend.TraceBackend;
import com.hmdp.agent.observability.backend.TraceBackendAssembler;
import com.hmdp.agent.observability.backend.impl.ConsoleBackend;
import com.hmdp.agent.observability.backend.impl.GenericOtlpBackend;
import com.hmdp.agent.observability.backend.impl.LangfuseBackend;
import com.hmdp.agent.observability.backend.impl.NoopBackend;
import com.hmdp.agent.observability.model.AgentSpanSpec;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link ObservabilityTraceFilter} 单元测试（无 Spring 上下文）。
 * <p>
 * 守护：默认前缀 = backend 能力（agent. 由 NAMESPACE 派生）且与 langfuse 现状一致；
 * 合并语义 = 默认 + 用户追加（用户不可删除默认项，评审 13.4-2）；放行判定。
 * </p>
 */
class ObservabilityTraceFilterTest {

    private BackendProperties props(String type) {
        BackendProperties p = new BackendProperties();
        p.setType(type);
        return p;
    }

    private ObservabilityTraceFilter filter(String type, List<String> userPrefixes) {
        List<TraceBackend> backends = List.of(
                new LangfuseBackend(),
                new GenericOtlpBackend.JaegerBackend(),
                new ConsoleBackend());
        TraceBackendAssembler assembler = new TraceBackendAssembler(backends, props(type));
        TraceProperties traceProps = new TraceProperties();
        if (userPrefixes != null) {
            traceProps.getTraceFilter().getIncludePrefixes().addAll(userPrefixes);
        }
        return new ObservabilityTraceFilter(traceProps, assembler);
    }

    @Test
    void defaultPrefixes_shouldMatchLangfuseStatusQuote_andDeriveAgentNamespace() {
        ObservabilityTraceFilter filter = filter("langfuse", null);
        // agent. 由 AgentSpanSpec.NAMESPACE 常量派生（评审 13.3.3），非字面量重复
        assertThat(AgentSpanSpec.NAMESPACE).isEqualTo("agent.");
        // 业务/LLM 层默认放行
        assertThat(filter.test("agent.session", null)).isTrue();
        assertThat(filter.test("spring.ai.chat.client", null)).isTrue();
        assertThat(filter.test("gen_ai.client.operation", null)).isTrue();
        // 非观测前缀（如 @Scheduled 任务）不放行
        assertThat(filter.test("load-cache", null)).isFalse();
        assertThat(filter.test("org.springframework.scheduling", null)).isFalse();
    }

    @Test
    void userAppend_shouldBeAdded_notOverrideDefaults() {
        ObservabilityTraceFilter filter = filter("langfuse", List.of("audit.", "my.biz."));
        assertThat(filter.test("audit.login", null)).isTrue();
        assertThat(filter.test("my.biz.xxx", null)).isTrue();
        // 默认项仍保留（用户不可删除）
        assertThat(filter.test("agent.round", null)).isTrue();
        assertThat(filter.test("gen_ai.client.operation", null)).isTrue();
    }

    @Test
    void genericOtlp_shouldKeepSameDefaultPrefixes() {
        ObservabilityTraceFilter filter = filter("jaeger", null);
        assertThat(filter.test("agent.session", null)).isTrue();
        assertThat(filter.test("spring.ai.chat.client", null)).isTrue();
        assertThat(filter.test("gen_ai.client.operation", null)).isTrue();
    }

    @Test
    void unknownTypeFallback_shouldUseNoopEmptyDefaults_plusUserAppend() {
        // Fail-Open 到 Noop：默认前缀为空 → 仅用户追加放行
        ObservabilityTraceFilter filter = filter("garbage", List.of("audit."));
        assertThat(filter.test("agent.session", null)).isFalse();
        assertThat(filter.test("spring.ai.chat.client", null)).isFalse();
        assertThat(filter.test("audit.login", null)).isTrue();
    }
}