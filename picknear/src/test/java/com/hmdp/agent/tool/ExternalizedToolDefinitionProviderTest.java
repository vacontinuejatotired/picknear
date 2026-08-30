package com.hmdp.agent.tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hmdp.agent.prompt.PromptService;
import com.hmdp.agent.prompt.config.PromptProperties;
import com.hmdp.agent.prompt.model.ResolvedToolPrompt;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * ExternalizedToolDefinitionProvider — 工具描述外置覆盖测试。
 * <p>
 * 覆盖：覆盖 description、patch inputSchema 参数描述、全链路失败回退原始定义、参数空跳过 patch。
 * </p>
 */
@ExtendWith(MockitoExtension.class)
class ExternalizedToolDefinitionProviderTest {

    @Mock private PromptService promptService;

    private PromptProperties props = new PromptProperties();
    private ExternalizedToolDefinitionProvider provider;

    @BeforeEach
    void setUp() {
        provider = new ExternalizedToolDefinitionProvider(promptService, props, new ObjectMapper());
    }

    private ToolCallback delegateWith(ToolDefinition raw) {
        ToolCallback delegate = mock(ToolCallback.class);
        when(delegate.getToolDefinition()).thenReturn(raw);
        return delegate;
    }

    @Test
    void should_override_description_and_patch_params() {
        ToolDefinition raw = ToolDefinition.builder()
                .name("queryWeather").description("原描述")
                .inputSchema("{\"type\":\"object\",\"properties\":{\"city\":{\"type\":\"string\",\"description\":\"原参数描述\"}}}")
                .build();
        when(promptService.renderTool(eq("agent.tool.queryWeather"), any()))
                .thenReturn(Optional.of(new ResolvedToolPrompt("外置描述", Map.of("city", "城市名称"))));

        ToolDefinition resolved = provider.resolve(delegateWith(raw));

        assertThat(resolved.description()).as("描述应被外置覆盖").isEqualTo("外置描述");
        assertThat(resolved.inputSchema())
                .as("参数描述应被覆盖")
                .contains("城市名称")
                .doesNotContain("原参数描述");
    }

    @Test
    void should_fallback_to_original_when_render_tool_empty() {
        ToolDefinition raw = ToolDefinition.builder()
                .name("queryWeather").description("原描述").inputSchema("{}").build();
        when(promptService.renderTool(eq("agent.tool.queryWeather"), any()))
                .thenReturn(Optional.empty());

        ToolDefinition resolved = provider.resolve(delegateWith(raw));

        assertThat(resolved).as("取不到时应返回 delegate 原始定义").isEqualTo(raw);
        assertThat(resolved.description()).isEqualTo("原描述");
    }

    @Test
    void should_keep_schema_when_no_params() {
        ToolDefinition raw = ToolDefinition.builder()
                .name("queryTotalBlogs").description("统计博客").inputSchema("{\"type\":\"object\"}")
                .build();
        when(promptService.renderTool(eq("agent.tool.queryTotalBlogs"), any()))
                .thenReturn(Optional.of(new ResolvedToolPrompt("统计博客数", Map.of())));

        ToolDefinition resolved = provider.resolve(delegateWith(raw));

        assertThat(resolved.inputSchema()).as("params 为空时 schema 原样保留").isEqualTo("{\"type\":\"object\"}");
    }

    @Test
    void should_not_break_on_invalid_schema() {
        ToolDefinition raw = ToolDefinition.builder()
                .name("queryWeather").description("原描述").inputSchema("不是JSON")
                .build();
        when(promptService.renderTool(eq("agent.tool.queryWeather"), any()))
                .thenReturn(Optional.of(new ResolvedToolPrompt("外置描述", Map.of("city", "x"))));

        ToolDefinition resolved = provider.resolve(delegateWith(raw));

        assertThat(resolved.description()).as("描述覆盖不受 schema 解析失败影响").isEqualTo("外置描述");
        assertThat(resolved.inputSchema()).as("schema 解析失败应保原样（Fail-Open）").isEqualTo("不是JSON");
    }
}
