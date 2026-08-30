package com.hmdp.agent.tool;

import com.hmdp.agent.annotation.TargetTool;
import com.hmdp.agent.config.PromptGuardProperties;
import com.hmdp.agent.guard.GuardedToolCallback;
import com.hmdp.agent.guard.ToolGuardManager;
import com.hmdp.agent.observability.api.AgentTracer;
import com.hmdp.agent.tool.impl.WeatherQueryTool;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.context.ApplicationContext;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * ToolBeanCollector — 工具回调收集器测试。
 * <p>
 * 覆盖 active 收集、inactive 跳过、GuardedToolCallback 包装、空 Bean。
 */
@ExtendWith(MockitoExtension.class)
class ToolBeanCollectorTest {

    @Mock
    private ApplicationContext appCtx;

    @Mock
    private ToolGuardManager guardManager;

    @Test
    void should_collect_active_tools() {
        when(appCtx.getBeansWithAnnotation(TargetTool.class))
                .thenReturn(Map.of("weatherTool", new WeatherQueryTool()));

        ToolBeanCollector collector = new ToolBeanCollector(guardManager, mock(AgentTracer.class), new PromptGuardProperties(), mock(ToolDefinitionProvider.class));
        collector.setApplicationContext(appCtx);

        assertThat(collector.getToolCallbacks())
                .as("WeatherQueryTool 有 1 个 @Tool 方法")
                .hasSize(1);
    }

    @Test
    void should_skip_inactive_tools() {
        when(appCtx.getBeansWithAnnotation(TargetTool.class))
                .thenReturn(Map.of("inactiveTool", new InactiveTestTool()));

        ToolBeanCollector collector = new ToolBeanCollector(guardManager, mock(AgentTracer.class), new PromptGuardProperties(), mock(ToolDefinitionProvider.class));
        collector.setApplicationContext(appCtx);

        assertThat(collector.getToolCallbacks())
                .as("inactive 工具应被跳过")
                .isEmpty();
    }

    @Test
    void should_wrap_with_guarded_tool_callback() {
        when(appCtx.getBeansWithAnnotation(TargetTool.class))
                .thenReturn(Map.of("weatherTool", new WeatherQueryTool()));

        ToolBeanCollector collector = new ToolBeanCollector(guardManager, mock(AgentTracer.class), new PromptGuardProperties(), mock(ToolDefinitionProvider.class));
        collector.setApplicationContext(appCtx);

        assertThat(collector.getToolCallbacks())
                .as("所有回调应被 GuardedToolCallback 包装")
                .allMatch(cb -> cb instanceof GuardedToolCallback);
    }

    @Test
    void should_return_empty_when_no_tools() {
        when(appCtx.getBeansWithAnnotation(TargetTool.class))
                .thenReturn(Map.of());

        ToolBeanCollector collector = new ToolBeanCollector(guardManager, mock(AgentTracer.class), new PromptGuardProperties(), mock(ToolDefinitionProvider.class));
        collector.setApplicationContext(appCtx);

        assertThat(collector.getToolCallbacks())
                .as("无 @TargetTool Bean 时应返回空数组")
                .isEmpty();
    }

    /** 测试用：active=false 的工具 Bean */
    @TargetTool(active = false)
    static class InactiveTestTool {
        @org.springframework.ai.tool.annotation.Tool(description = "test")
        public String testMethod() {
            return "test";
        }
    }
}
