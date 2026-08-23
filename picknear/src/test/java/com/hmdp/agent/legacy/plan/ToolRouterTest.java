package com.hmdp.agent.legacy.plan;

import com.hmdp.agent.config.FeatureProperties;
import com.hmdp.agent.legacy.plan.ToolRouter;
import com.hmdp.agent.plan.routing.CompactCatalogBuilder;
import com.hmdp.agent.plan.model.TaskReport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * ToolRouter — 规划工具路由门面测试。
 * <p>
 * 覆盖 buildCatalog(true/false) 差异（紧凑 vs 全量）、isUncertain 命中/未命中、
 * 配置缺失时 maxTagLength 回退默认值。
 * </p>
 */
@ExtendWith(MockitoExtension.class)
class ToolRouterTest {

    @Mock
    private CompactCatalogBuilder compactCatalogBuilder;

    @Mock
    private FeatureProperties featureProperties;

    @Mock
    private FeatureProperties.ToolRouting toolRouting;

    @InjectMocks
    private ToolRouter toolRouter;

    private ToolCallback weatherCallback;
    private TaskReport history;

    @BeforeEach
    void setUp() {
        ToolDefinition def = ToolDefinition.builder()
                .name("queryWeather")
                .description("查询某城市的当前天气（温度、晴雨）。")
                .inputSchema("{\"type\":\"object\",\"properties\":{\"city\":{\"type\":\"string\"}}}")
                .build();
        weatherCallback = mock(ToolCallback.class);
        // lenient：仅全量目录测试用到该定义，其余测试走 mock 的 builder 不触达 callback
        lenient().when(weatherCallback.getToolDefinition()).thenReturn(def);
        history = new TaskReport();
    }

    @Test
    void should_use_compact_builder_when_compact() {
        when(featureProperties.getToolRouting()).thenReturn(toolRouting);
        when(toolRouting.getMaxTagLength()).thenReturn(60);
        when(compactCatalogBuilder.build(any(), any(), anyInt(), anyString())).thenReturn("紧凑目录");
        ToolCallback[] callbacks = new ToolCallback[]{weatherCallback};

        String catalog = toolRouter.buildCatalog(true, callbacks, history, "");

        assertThat(catalog).isEqualTo("紧凑目录");
        verify(compactCatalogBuilder).build(eq(callbacks), eq(history), eq(60), eq(""));
    }

    @Test
    void should_build_full_catalog_with_full_descriptions_when_not_compact() {
        String catalog = toolRouter.buildCatalog(false, new ToolCallback[]{weatherCallback}, history, "");

        assertThat(catalog).isEqualTo("- queryWeather: 查询某城市的当前天气（温度、晴雨）。\n");
        verify(compactCatalogBuilder, never()).build(any(), any(), anyInt(), anyString());
    }

    @Test
    void should_fallback_to_default_max_tag_length_when_config_missing() {
        // featureProperties mock 默认 getToolRouting()=null → maxTagLength 回退 60
        when(compactCatalogBuilder.build(any(), any(), anyInt(), anyString())).thenReturn("x");

        toolRouter.buildCatalog(true, new ToolCallback[]{weatherCallback}, history, "");

        verify(compactCatalogBuilder).build(any(), any(), eq(60), eq(""));
    }

    @Test
    void is_uncertain_should_detect_marker() {
        assertThat(toolRouter.isUncertain(
                "===PLAN_START===[{\"tool\":\"__UNCERTAIN__\"}]===PLAN_END===")).isTrue();
        assertThat(toolRouter.isUncertain("[{\"tool\":\"queryWeather\"}]")).isFalse();
        assertThat(toolRouter.isUncertain(null)).isFalse();
        assertThat(toolRouter.isUncertain("")).isFalse();
    }
}
