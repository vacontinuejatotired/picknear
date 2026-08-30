package com.hmdp.agent.observability.core;

import com.hmdp.agent.observability.backend.TraceBackend;
import com.hmdp.agent.observability.backend.impl.GenericOtlpBackend;
import com.hmdp.agent.observability.backend.impl.LangfuseBackend;
import com.hmdp.agent.observability.model.AgentSpanSpec;
import com.hmdp.agent.observability.support.AttributeSanitizer;
import com.hmdp.agent.observability.support.TriState;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link SpanNamingStrategy} 纯单元测试（评审 13.2.2：语义编码 = 后端能力推导的单一事实源）。
 * <p>
 * 守护点：Langfuse（不展示属性）→ 编码开、语义进 span 名（现状行为不回退）；
 * Jaeger 等（展示属性）→ 编码关、span 名无后缀（语义靠 AgentField 属性）；
 * guard 结构化语义两条路径；null/空白语义不拼。
 * </p>
 */
class SpanNamingStrategyTest {

    private final SpanNameEncoder encoder = new SpanNameEncoder(new AttributeSanitizer());

    private SpanNamingStrategy strategy(TraceBackend backend) {
        return new SpanNamingStrategy(backend, encoder);
    }

    @Test
    void langfuse_shouldEnableSemanticEncoding_byCapabilityDerivation() {
        SpanNamingStrategy strategy = strategy(new LangfuseBackend());
        assertThat(strategy.semanticEncoding()).as("Langfuse 不展示属性 → 语义编码开启").isTrue();
        // 现状行为不回退：语义后缀照旧拼入 span 名
        assertThat(strategy.name(AgentSpanSpec.TOOL_CALL, "queryShop"))
                .isEqualTo("agent.tool_call.queryShop");
    }

    @Test
    void genericOtlp_shouldDisableSemanticEncoding() {
        SpanNamingStrategy strategy = strategy(new GenericOtlpBackend.JaegerBackend());
        assertThat(strategy.semanticEncoding()).as("Jaeger 展示属性 → 语义编码关闭").isFalse();
        // span 名回归无噪音：语义经 attributes 展示
        assertThat(strategy.name(AgentSpanSpec.TOOL_CALL, "queryShop"))
                .isEqualTo("agent.tool_call");
    }

    @Test
    void name_shouldOmitSemantic_whenNullOrBlank() {
        SpanNamingStrategy strategy = strategy(new LangfuseBackend());
        assertThat(strategy.name(AgentSpanSpec.ROUND, null)).isEqualTo("agent.round");
        assertThat(strategy.name(AgentSpanSpec.ROUND, "  ")).isEqualTo("agent.round");
    }

    @Test
    void name_shouldSanitizeSemantic_beforeAppending() {
        SpanNamingStrategy strategy = strategy(new LangfuseBackend());
        // 语义进 span 名前清洗（去空白/控制字符）
        assertThat(strategy.name(AgentSpanSpec.TOOL_CALL, "query Shop ")).isEqualTo("agent.tool_call.queryShop");
    }

    @Test
    void guardName_langfuse_shouldEncodeStructuredSemantic() {
        SpanNamingStrategy strategy = strategy(new LangfuseBackend());
        assertThat(strategy.guardName(AgentSpanSpec.GUARD, "BLOCK", "deleteBlog", "qwen-plus", "{\"id\": 1}"))
                .isEqualTo("agent.guard.BLOCK.deleteBlog.qwen-plus.{id:1}");
    }

    @Test
    void guardName_genericOtlp_shouldFallbackToPlainName() {
        SpanNamingStrategy strategy = strategy(new GenericOtlpBackend.JaegerBackend());
        // 编码关闭：guard 语义只进属性（GUARD_DECISION/TOOL_NAME/MODEL_NAME/TOOL_ARGUMENTS）
        assertThat(strategy.guardName(AgentSpanSpec.GUARD, "BLOCK", "deleteBlog", "qwen-plus", "{\"id\": 1}"))
                .isEqualTo("agent.guard");
    }

    @Test
    void guardName_shouldOmitSemantic_whenDecisionOrToolBlank() {
        SpanNamingStrategy strategy = strategy(new LangfuseBackend());
        assertThat(strategy.guardName(AgentSpanSpec.GUARD, "", "tool", null, null))
                .isEqualTo("agent.guard");
    }

    // ════ S5：用户级能力覆盖开关（auto=跟能力；true/false 强制） ════

    @Test
    void overrideEnabled_shouldForceEncoding_evenOnAttributeVisibleBackend() {
        // Jaeger 能力推导为不编码，但用户强制 true → 语义编码开（调试用）
        SpanNamingStrategy strategy = new SpanNamingStrategy(
                new GenericOtlpBackend.JaegerBackend(), encoder, TriState.ENABLED);
        assertThat(strategy.semanticEncoding()).isTrue();
        assertThat(strategy.name(AgentSpanSpec.TOOL_CALL, "queryShop"))
                .isEqualTo("agent.tool_call.queryShop");
        assertThat(strategy.guardName(AgentSpanSpec.GUARD, "BLOCK", "deleteBlog", null, "{\"id\": 1}"))
                .isEqualTo("agent.guard.BLOCK.deleteBlog.{id:1}");
    }

    @Test
    void overrideDisabled_shouldForceNoEncoding_evenOnLangfuse() {
        // Langfuse 能力推导为编码，但用户强制 false → 语义靠属性展示（口径迁移）
        SpanNamingStrategy strategy = new SpanNamingStrategy(
                new LangfuseBackend(), encoder, TriState.DISABLED);
        assertThat(strategy.semanticEncoding()).isFalse();
        assertThat(strategy.name(AgentSpanSpec.TOOL_CALL, "queryShop"))
                .isEqualTo("agent.tool_call");
        assertThat(strategy.guardName(AgentSpanSpec.GUARD, "BLOCK", "deleteBlog", null, "{\"id\": 1}"))
                .isEqualTo("agent.guard");
    }

    @Test
    void overrideAuto_shouldFollowBackendCapability() {
        SpanNamingStrategy langfuse = new SpanNamingStrategy(
                new LangfuseBackend(), encoder, TriState.AUTO);
        assertThat(langfuse.semanticEncoding()).isTrue();
        SpanNamingStrategy jaeger = new SpanNamingStrategy(
                new GenericOtlpBackend.JaegerBackend(), encoder, TriState.AUTO);
        assertThat(jaeger.semanticEncoding()).isFalse();
    }
}