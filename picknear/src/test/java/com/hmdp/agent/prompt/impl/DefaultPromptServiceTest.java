package com.hmdp.agent.prompt.impl;

import com.hmdp.agent.observability.api.AgentSpan;
import com.hmdp.agent.observability.api.AgentTracer;
import com.hmdp.agent.observability.model.AgentSpanSpec;
import com.hmdp.agent.prompt.PromptService;
import com.hmdp.agent.prompt.config.PromptProperties;
import com.hmdp.agent.prompt.repo.BuiltinPromptRepository;
import com.hmdp.agent.prompt.repo.LangfusePromptRepository;
import com.hmdp.agent.prompt.repo.LocalPromptRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * DefaultPromptService — 编排测试（远程 → 内置 → Fail-Open）。
 */
@ExtendWith(MockitoExtension.class)
class DefaultPromptServiceTest {

    @Mock private LangfusePromptRepository remote;
    @Mock private LocalPromptRepository local;
    @Mock private BuiltinPromptRepository builtin;
    @Mock private AgentTracer agentTracer;

    private PromptProperties props;

    @BeforeEach
    void setUp() {
        props = new PromptProperties();
        lenient().when(agentTracer.start(any(AgentSpanSpec.class), any()))
                .thenReturn(mock(AgentSpan.class));
    }

    private PromptService service() {
        return new DefaultPromptService(props, remote, local, builtin, agentTracer);
    }

    @Test
    void should_use_builtin_when_disabled() {
        props.setEnabled(false);
        when(builtin.load("k")).thenReturn(Optional.of("内置"));

        assertThat(service().render("k", Map.of())).isEqualTo("内置");
        verify(remote, never()).fetch(anyString());
    }

    @Test
    void should_use_builtin_when_remote_not_configured() {
        // 默认 baseUrl/basicAuth 为空 → isConfigured false
        when(builtin.load("k")).thenReturn(Optional.of("内置"));

        assertThat(service().render("k", Map.of())).isEqualTo("内置");
        verify(remote, never()).fetch(anyString());
    }

    @Test
    void should_use_remote_when_configured_and_present() {
        props.setBaseUrl("https://x");
        props.setBasicAuth("y");
        when(remote.fetch("k")).thenReturn(Optional.of("远程 {{a}}"));

        assertThat(service().render("k", Map.of("a", "1"))).isEqualTo("远程 1");
    }

    @Test
    void should_fallback_to_builtin_when_remote_empty() {
        props.setBaseUrl("https://x");
        props.setBasicAuth("y");
        when(remote.fetch("k")).thenReturn(Optional.empty());
        when(builtin.load("k")).thenReturn(Optional.of("内置"));

        assertThat(service().render("k", Map.of())).isEqualTo("内置");
    }

    @Test
    void should_return_empty_when_builtin_missing() {
        props.setEnabled(false);
        when(builtin.load("k")).thenReturn(Optional.empty());

        assertThat(service().render("k", Map.of())).as("双缺失应返回空串而非抛异常").isEmpty();
    }

    @Test
    void should_return_empty_for_missing_tool_template() {
        props.setBaseUrl("https://x");
        props.setBasicAuth("y");
        when(remote.fetch("agent.tool.queryWeather")).thenReturn(Optional.empty());
        when(builtin.load("agent.tool.queryWeather")).thenReturn(Optional.empty());

        assertThat(service().renderTool("agent.tool.queryWeather", Map.of()))
                .as("远程+内置都缺失 → empty（回退 @Tool 注解）").isEmpty();
    }

    @Test
    void should_parse_tool_template_from_remote() {
        props.setBaseUrl("https://x");
        props.setBasicAuth("y");
        when(remote.fetch("agent.tool.queryWeather"))
                .thenReturn(Optional.of("{\"description\":\"查天气\",\"params\":{\"city\":\"城市名称\"}}"));

        var tp = service().renderTool("agent.tool.queryWeather", Map.of());

        assertThat(tp).isPresent();
        assertThat(tp.get().description()).isEqualTo("查天气");
        assertThat(tp.get().params()).containsEntry("city", "城市名称");
    }
}
