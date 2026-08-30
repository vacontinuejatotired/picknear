package com.hmdp.agent.plan;

import com.hmdp.agent.legacy.plan.LegacyPlanRouter;
import com.hmdp.agent.legacy.plan.ToolRouter;
import com.hmdp.agent.plan.model.PlanOutcome;
import com.hmdp.agent.plan.model.PlanRequest;
import com.hmdp.agent.prompt.PromptKeys;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * LegacyPlanRouter — legacy 规划策略测试（紧凑目录 + UNCERTAIN 全量重跑）。
 */
@ExtendWith(MockitoExtension.class)
class LegacyPlanRouterTest {

    @Mock private PlanSupport support;
    @Mock private ToolRouter toolRouter;

    private LegacyPlanRouter router;
    private final ToolCallback[] callbacks = new ToolCallback[]{};
    private final TaskReport history = new TaskReport();
    private PlanRequest req;

    @BeforeEach
    void setUp() {
        router = new LegacyPlanRouter(support, toolRouter);
        req = new PlanRequest("天气", "好的", null, callbacks, history);
    }

    @Test
    void should_return_phase1_direct_parse() {
        SubTask task = SubTask.builder().toolName("queryWeather").type(TaskType.TOOL_CALL).build();
        when(support.parseAndValidate(eq(req), eq("好的"), any())).thenReturn(List.of(task));

        PlanOutcome outcome = router.plan(req);

        assertThat(outcome.source()).isEqualTo("from_response");
        verify(toolRouter, never()).buildCatalog(anyBoolean(), any(), any(), anyString());
    }

    @Test
    void should_retry_full_catalog_when_uncertain() {
        when(support.parseAndValidate(eq(req), eq("好的"), any())).thenReturn(List.of());
        when(toolRouter.buildCatalog(eq(true), any(), any(), anyString())).thenReturn("紧凑目录");
        when(toolRouter.buildCatalog(eq(false), any(), any(), anyString())).thenReturn("全量目录");
        when(support.plannerCall(eq(req), eq("紧凑目录"), eq(PromptKeys.PLANNER_USER))).thenReturn("__UNCERTAIN__");
        when(support.plannerCall(eq(req), eq("全量目录"), eq(PromptKeys.PLANNER_USER))).thenReturn("[]");
        when(toolRouter.isUncertain("__UNCERTAIN__")).thenReturn(true);
        when(support.parseAndValidate(eq(req), eq("[]"), any())).thenReturn(List.of());

        PlanOutcome outcome = router.plan(req);

        assertThat(outcome.source()).isEqualTo("empty");
        verify(support, times(2)).plannerCall(eq(req), anyString(), eq(PromptKeys.PLANNER_USER));
        verify(toolRouter).buildCatalog(eq(true), any(), any(), anyString());
        verify(toolRouter).buildCatalog(eq(false), any(), any(), anyString());
    }

    @Test
    void should_plan_once_when_not_uncertain() {
        when(support.parseAndValidate(eq(req), eq("好的"), any())).thenReturn(List.of());
        when(toolRouter.buildCatalog(eq(true), any(), any(), anyString())).thenReturn("紧凑目录");
        when(support.plannerCall(eq(req), eq("紧凑目录"), eq(PromptKeys.PLANNER_USER))).thenReturn("[]");
        when(toolRouter.isUncertain("[]")).thenReturn(false);
        when(support.parseAndValidate(eq(req), eq("[]"), any())).thenReturn(List.of());

        router.plan(req);

        verify(support, times(1)).plannerCall(eq(req), anyString(), eq(PromptKeys.PLANNER_USER));
        verify(toolRouter, times(1)).buildCatalog(eq(true), any(), any(), anyString());
    }
}
