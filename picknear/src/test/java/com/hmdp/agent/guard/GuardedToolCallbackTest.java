package com.hmdp.agent.guard;

import com.hmdp.agent.guard.model.GuardResult;
import com.hmdp.agent.observability.api.AgentSpan;
import com.hmdp.agent.observability.api.AgentTracer;
import com.hmdp.agent.observability.model.AgentSpanSpec;
import com.hmdp.agent.observability.support.AttributeSanitizer;
import com.hmdp.agent.tool.ToolDefinitionProvider;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.*;

/**
 * GuardedToolCallback — 守卫包装回调测试。
 * <p>
 * 覆盖 ALLOW/BLOCK/CONFIRM 三种决策路径、returnDirect 开关、ToolContext 透传，
 * 以及工具描述外置（ToolDefinitionProvider）的覆盖与回退。
 * </p>
 */
@ExtendWith(MockitoExtension.class)
class GuardedToolCallbackTest {

    @Mock
    private ToolCallback delegate;

    @Mock
    private ToolGuardManager guardManager;

    @Mock
    private AgentTracer tracer;

    private final ToolDefinition toolDef = ToolDefinition.builder()
            .name("testTool").description("测试工具").inputSchema("{}").build();

    @BeforeEach
    void setUp() {
        lenient().when(delegate.getToolDefinition()).thenReturn(toolDef);
        // 生产 AgentTracer.start 失败时返回 NoopAgentSpan，永不 null；测试桩一个非 null span
        lenient().when(tracer.start(any(), any())).thenReturn(mock(AgentSpan.class));
    }

    @Test
    void should_delegate_call_when_allowed() {
        when(guardManager.evaluate(any())).thenReturn(GuardResult.allow());
        when(delegate.call(anyString(), any(ToolContext.class))).thenReturn("delegate result");

        GuardedToolCallback guarded = new GuardedToolCallback(delegate, guardManager, "conv-1", 1L, false, tracer);

        String result = guarded.call("{}");

        assertThat(result).as("ALLOW 时应返回 delegate 结果").isEqualTo("delegate result");
        verify(delegate).call(eq("{}"), any(ToolContext.class));
    }

    @Test
    void should_truncate_tool_result_when_exceeds_max() {
        when(guardManager.evaluate(any())).thenReturn(GuardResult.allow());
        when(delegate.call(anyString(), any(ToolContext.class))).thenReturn("a".repeat(100));

        GuardedToolCallback guarded = new GuardedToolCallback(delegate, guardManager, "conv-1", 1L, false,
                tracer, true, ToolCallback::getToolDefinition, 10);

        String result = guarded.call("{}");

        assertThat(result).as("ALLOW 结果应截断至 maxChars（10 字符 + 省略号）").isEqualTo("aaaaaaaaaa...");
        verify(delegate).call(eq("{}"), any(ToolContext.class));
    }

    @Test
    void should_truncate_codepoint_safe_on_emoji() {
        when(guardManager.evaluate(any())).thenReturn(GuardResult.allow());
        when(delegate.call(anyString(), any(ToolContext.class))).thenReturn("😀".repeat(20));

        GuardedToolCallback guarded = new GuardedToolCallback(delegate, guardManager, "conv-1", 1L, false,
                tracer, true, ToolCallback::getToolDefinition, 10);

        String result = guarded.call("{}");

        // 10 个完整 emoji（每个 1 码点 / 2 UTF-16 字符）+ "..."，不产生孤立代理对
        assertThat(result).as("emoji 截断应完整、无孤立代理对").hasSize(10 * 2 + 3);
    }

    @Test
    void should_return_error_json_when_blocked() {
        when(guardManager.evaluate(any())).thenReturn(GuardResult.block("安全策略拦截", "HighRiskListPolicy"));

        GuardedToolCallback guarded = new GuardedToolCallback(delegate, guardManager, "conv-1", 1L, false, tracer);

        String result = guarded.call("{}");

        assertThat(result).as("BLOCK 应返回错误 JSON").contains("error");
        verify(delegate, never()).call(anyString());
    }

    @Test
    void should_return_direct_text_when_blocked_and_return_direct() {
        when(guardManager.evaluate(any())).thenReturn(GuardResult.block("安全策略拦截", "HighRiskListPolicy"));

        GuardedToolCallback guarded = new GuardedToolCallback(delegate, guardManager, "conv-1", 1L, true, tracer);

        String result = guarded.call("{}");

        assertThat(result).as("BLOCK + returnDirect 应返回纯文本").isEqualTo("安全策略拦截");
        verify(delegate, never()).call(anyString());
    }

    @Test
    void should_return_confirm_json_when_confirm() {
        when(guardManager.evaluate(any())).thenReturn(GuardResult.confirm("需要确认", "ConfirmToolPolicy"));

        // approvalEnabled=false：CONFIRM 退回确认 JSON（审批开启时才会抛 ConfirmRequiredException）
        GuardedToolCallback guarded = new GuardedToolCallback(delegate, guardManager, "conv-1", 1L, false,
                tracer, false, ToolCallback::getToolDefinition, 1200);

        String result = guarded.call("{}");

        assertThat(result).as("CONFIRM 应返回确认提示 JSON").contains("confirm");
        verify(delegate, never()).call(anyString());
    }

    @Test
    void should_override_tool_definition_via_provider() {
        ToolDefinition external = ToolDefinition.builder()
                .name("testTool").description("外置描述").inputSchema("{}").build();
        ToolDefinitionProvider provider = d -> external;

        GuardedToolCallback guarded = new GuardedToolCallback(delegate, guardManager, "conv-1", 1L, false,
                tracer, true, provider, 1200);

        assertThat(guarded.getToolDefinition().description()).as("描述应来自外置 provider").isEqualTo("外置描述");
    }

    @Test
    void should_fallback_to_delegate_definition_by_default() {
        // 便捷构造走 identity provider：getToolDefinition 应返回 delegate 原始定义
        GuardedToolCallback guarded = new GuardedToolCallback(delegate, guardManager, "conv-1", 1L, false, tracer);

        assertThat(guarded.getToolDefinition().description()).isEqualTo("测试工具");
    }

    @Test
    void should_read_user_id_from_tool_context() {
        when(guardManager.evaluate(any())).thenReturn(GuardResult.allow());
        when(delegate.call(anyString(), any(ToolContext.class))).thenReturn("ok");

        GuardedToolCallback guarded = new GuardedToolCallback(delegate, guardManager, "conv-1", 1L, false, tracer);

        guarded.call("{}", new ToolContext(Map.of("userId", 42L)));

        verify(guardManager).evaluate(argThat(ic -> ic.getUserId().equals(42L)));
    }

    @Test
    void should_resolve_self_user_id_placeholder_in_allowed_call() {
        ToolDefinition userDef = ToolDefinition.builder()
                .name("queryUserBlogs").description("查用户博客").inputSchema("{}").build();
        when(delegate.getToolDefinition()).thenReturn(userDef);
        when(guardManager.evaluate(any())).thenReturn(GuardResult.allow());
        when(delegate.call(anyString(), any(ToolContext.class))).thenReturn("ok");

        GuardedToolCallback guarded = new GuardedToolCallback(delegate, guardManager, "conv-1", 42L, false, tracer);

        guarded.call("{\"userId\":\"self\"}", new ToolContext(Map.of("userId", 42L)));

        verify(delegate).call(eq("{\"userId\":42}"), any(ToolContext.class));
    }

    @Test
    void should_resolve_self_user_id_placeholder_in_call_bypass() {
        ToolDefinition userDef = ToolDefinition.builder()
                .name("queryUserBlogs").description("查用户博客").inputSchema("{}").build();
        when(delegate.getToolDefinition()).thenReturn(userDef);
        when(delegate.call(anyString(), any(ToolContext.class))).thenReturn("ok");

        GuardedToolCallback guarded = new GuardedToolCallback(delegate, guardManager, "conv-1", 42L, false, tracer);

        guarded.callBypass("{\"userId\":\"self\"}", new ToolContext(Map.of("userId", 42L)));

        verify(delegate).call(eq("{\"userId\":42}"), any(ToolContext.class));
    }

    @Test
    void should_not_resolve_when_user_id_null_in_call_bypass() {
        ToolDefinition userDef = ToolDefinition.builder()
                .name("queryUserBlogs").description("查用户博客").inputSchema("{}").build();
        when(delegate.getToolDefinition()).thenReturn(userDef);
        when(delegate.call(anyString(), any(ToolContext.class))).thenReturn("ok");

        GuardedToolCallback guarded = new GuardedToolCallback(delegate, guardManager, "conv-1", 42L, false, tracer);

        guarded.callBypass("{\"userId\":\"self\"}", new ToolContext(Map.of()));

        verify(delegate).call(eq("{\"userId\":\"self\"}"), any(ToolContext.class));
    }

    @Test
    void should_pass_raw_semantics_to_startGuard() {
        when(guardManager.evaluate(any())).thenReturn(GuardResult.allow());
        when(delegate.call(anyString(), any(ToolContext.class))).thenReturn("ok");

        GuardedToolCallback guarded = new GuardedToolCallback(delegate, guardManager, "conv-1", 1L, false,
                tracer, true, ToolCallback::getToolDefinition, 1200,
                "qwen-plus-2025-07-28");

        guarded.call("{\"city\":\"北京\"}");

        // 语义内容原样透传（decision/toolName/modelName/payload）；拼名/脱敏/限长已下沉
        // SpanNameEncoder（见 SpanNameEncoderTest），业务侧不再持有加工逻辑（评审 13.3.2）
        verify(tracer).startGuard(eq("ALLOW"), eq("testTool"), eq("qwen-plus-2025-07-28"), eq("{\"city\":\"北京\"}"));
    }

    @Test
    void should_omit_model_and_args_when_not_injected() {
        when(guardManager.evaluate(any())).thenReturn(GuardResult.allow());
        when(delegate.call(anyString(), any(ToolContext.class))).thenReturn("ok");

        // 便捷构造（未注入模型名）→ 原始语义中 modelName 为 null、payload 原样传
        GuardedToolCallback guarded = new GuardedToolCallback(delegate, guardManager, "conv-1", 1L, false, tracer);

        guarded.call("{}");

        verify(tracer).startGuard(eq("ALLOW"), eq("testTool"), isNull(), eq("{}"));
    }

    @Test
    void should_pass_raw_payload_to_startGuard_without_processing() {
        when(guardManager.evaluate(any())).thenReturn(GuardResult.allow());
        when(delegate.call(anyString(), any(ToolContext.class))).thenReturn("ok");

        GuardedToolCallback guarded = new GuardedToolCallback(delegate, guardManager, "conv-1", 1L, false,
                tracer, true, ToolCallback::getToolDefinition, 1200,
                "qwen-plus-2025-07-28");

        guarded.call("{\"phone\":\"13812345678\"}");

        // 参数原文透传（含手机号）——脱敏发生在 SpanNameEncoder.compactArgs（统一脱敏出口），
        // 业务侧不再做任何载荷加工（评审 13.3.2 铁律：语义留业务、加工沉策略）
        verify(tracer).startGuard(eq("ALLOW"), eq("testTool"), eq("qwen-plus-2025-07-28"),
                eq("{\"phone\":\"13812345678\"}"));
    }
}
