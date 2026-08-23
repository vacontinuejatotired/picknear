package com.hmdp.agent.plan;

import com.hmdp.agent.config.FeatureProperties;
import com.hmdp.agent.plan.model.PlanOutcome;
import com.hmdp.agent.plan.model.PlanRequest;
import com.hmdp.agent.prompt.PromptKeys;
import com.hmdp.agent.plan.routing.TreeCatalogBuilder;
import com.hmdp.agent.plan.model.SubTask;
import com.hmdp.agent.plan.model.TaskReport;
import com.hmdp.agent.plan.model.TaskType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.tool.ToolCallback;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * TreePlanRouter — 两级路由策略测试。
 */
@ExtendWith(MockitoExtension.class)
class TreePlanRouterTest {

    @Mock private PlanSupport support;
    @Mock private TreeCatalogBuilder treeCatalogBuilder;
    @Mock private FeatureProperties featureProperties;
    @Mock private FeatureProperties.ToolRouting toolRoutingFeature;
    @Mock private com.hmdp.agent.plan.intent.ToolIntentTree intentTree;

    private TreePlanRouter router;
    private final ToolCallback[] callbacks = new ToolCallback[]{};
    private final TaskReport history = new TaskReport();
    private PlanRequest req;

    @BeforeEach
    void setUp() {
        router = new TreePlanRouter(support, treeCatalogBuilder, featureProperties, intentTree);
        req = new PlanRequest("我的博客和长沙天气", "好的", 100L, callbacks, history);
        lenient().when(featureProperties.getToolRouting()).thenReturn(toolRoutingFeature);
        lenient().when(toolRoutingFeature.getMaxTagLength()).thenReturn(60);
        lenient().when(intentTree.matchNodes(anyString())).thenReturn(java.util.Set.of());
    }

    @Test
    void should_skip_planner_when_catalog_blank() {
        when(support.parseAndValidate(eq(req), eq("好的"), any())).thenReturn(List.of());
        when(treeCatalogBuilder.build(any(), any(), anyInt(), anyString())).thenReturn("");

        PlanOutcome outcome = router.plan(req);

        assertThat(outcome.source()).as("空命中 source=empty").isEqualTo("empty");
        assertThat(outcome.tasks()).isEmpty();
        verify(support, never()).plannerCall(any(), any(), any());
    }

    @Test
    void should_return_phase1_direct_parse() {
        SubTask task = SubTask.builder().toolName("queryWeather").type(TaskType.TOOL_CALL).build();
        when(support.parseAndValidate(eq(req), eq("好的"), any())).thenReturn(List.of(task));

        PlanOutcome outcome = router.plan(req);

        assertThat(outcome.source()).as("Phase1 直解 source=from_response").isEqualTo("from_response");
        assertThat(outcome.tasks()).hasSize(1);
        verify(treeCatalogBuilder, never()).build(any(), any(), anyInt(), anyString());
    }

    @Test
    void should_plan_via_v2_template_when_catalog_non_blank() {
        String rawPlan = "{\"intents\":[\"查询→博客\"],\"plan\":[]}";
        when(support.parseAndValidate(eq(req), eq("好的"), any())).thenReturn(List.of());
        when(treeCatalogBuilder.build(any(), any(), anyInt(), anyString())).thenReturn("【查询】【博客】");
        when(support.plannerCall(eq(req), anyString(), eq(PromptKeys.PLANNER_USER_V2))).thenReturn(rawPlan);
        SubTask task = SubTask.builder().toolName("queryPublishedBlogs").type(TaskType.TOOL_CALL).build();
        when(support.parseAndValidate(eq(req), eq(rawPlan), any())).thenReturn(List.of(task));

        PlanOutcome outcome = router.plan(req);

        assertThat(outcome.source()).as("规划产出 source=ai_plan").isEqualTo("ai_plan");
        assertThat(outcome.tasks()).hasSize(1);
        verify(support).plannerCall(eq(req), anyString(), eq(PromptKeys.PLANNER_USER_V2));
    }
}
