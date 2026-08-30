package com.hmdp.agent.orchestration;

import com.hmdp.agent.config.FeatureProperties;
import com.hmdp.agent.context.AgentContext;
import com.hmdp.agent.observability.api.AgentSpan;
import com.hmdp.agent.observability.api.AgentTracer;
import com.hmdp.agent.observability.model.AgentSpanSpec;
import com.hmdp.agent.orchestration.confirm.ConfirmFlowManager;
import com.hmdp.agent.orchestration.round.FallbackRoundExecutor;
import com.hmdp.agent.orchestration.round.RoundExecutionProxy;
import com.hmdp.agent.plan.model.PlanOutcome;
import com.hmdp.agent.plan.model.PlanRequest;
import com.hmdp.agent.plan.PlanRouter;
import com.hmdp.agent.plan.model.SubTask;
import com.hmdp.agent.plan.model.TaskReport;
import com.hmdp.agent.plan.model.TaskType;
import com.hmdp.agent.tool.ToolBeanCollector;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * MultiRoundOrchestrator — 规划主循环测试（原 TaskPlanner 主循环拆出后归属）。
 * <p>
 * 规划细节已下沉到 {@link com.hmdp.agent.plan.PlanRouter} 策略（各自有独立测试），本测试只验证：
 * decompose 委托 PlanRouter 并透传任务、runLoop 主循环（子 Agent 路径）。
 * </p>
 */
@ExtendWith(MockitoExtension.class)
class MultiRoundOrchestratorTest {

    @Mock private ToolBeanCollector toolBeanCollector;
    @Mock private ToolCallback weatherCallback;
    @Mock private FeatureProperties featureProperties;
    @Mock private FeatureProperties.SubAgent subAgentFeature;
    @Mock private AgentTracer agentTracer;
    @Mock private PlanRouter planRouter;
    @Mock private ConfirmFlowManager confirmFlowManager;
    @Mock private RoundExecutionProxy roundExecutionProxy;
    @Mock private FallbackRoundExecutor fallbackRoundExecutor;

    private ToolDefinition weatherDef;
    private final TaskReport emptyHistory = new TaskReport();

    @InjectMocks
    private MultiRoundOrchestrator orchestrator;

    private final AgentContext ctx = AgentContext.builder().userId(1L).build();

    @BeforeEach
    void setUp() {
        weatherDef = ToolDefinition.builder()
                .name("queryWeather").description("查天气").inputSchema("{}").build();

        lenient().when(weatherCallback.getToolDefinition()).thenReturn(weatherDef);
        lenient().when(featureProperties.getSubagent()).thenReturn(subAgentFeature);
        lenient().when(agentTracer.start(any(AgentSpanSpec.class), any())).thenReturn(mock(AgentSpan.class));
    }

    private SubTask weatherTask() {
        return SubTask.builder()
                .toolName("queryWeather")
                .type(TaskType.TOOL_CALL)
                .params(Map.of("city", "北京"))
                .build();
    }

    // ═══════════════════════════════════════════════════════
    // decompose：委托 PlanRouter
    // ═══════════════════════════════════════════════════════

    @Test
    void decompose_should_delegate_to_plan_router() {
        when(planRouter.plan(any(PlanRequest.class)))
                .thenReturn(PlanOutcome.of(List.of(weatherTask()), "ai_plan"));

        List<SubTask> tasks = orchestrator.decompose("北京天气", "好的",
                new ToolCallback[]{weatherCallback}, emptyHistory, null);

        assertThat(tasks).as("应透传 PlanRouter 产出").hasSize(1);
        assertThat(tasks.get(0).getToolName()).isEqualTo("queryWeather");
        assertThat(tasks.get(0).getParams()).containsEntry("city", "北京");

        ArgumentCaptor<PlanRequest> captor = ArgumentCaptor.forClass(PlanRequest.class);
        verify(planRouter).plan(captor.capture());
        assertThat(captor.getValue().userInput()).as("应把用户输入传给 PlanRouter").isEqualTo("北京天气");
    }

    @Test
    void decompose_should_return_empty_when_router_empty() {
        when(planRouter.plan(any(PlanRequest.class)))
                .thenReturn(PlanOutcome.of(List.of(), "empty"));

        assertThat(orchestrator.decompose("模糊", "好的",
                new ToolCallback[]{weatherCallback}, emptyHistory, null)).as("空计划应透传").isEmpty();
    }

    // ═══════════════════════════════════════════════════════
    // runLoop 主循环（子 Agent 路径）
    // ═══════════════════════════════════════════════════════

    @Test
    void should_return_response_when_no_tasks() {
        when(planRouter.plan(any(PlanRequest.class)))
                .thenReturn(PlanOutcome.of(List.of(), "empty"));
        when(toolBeanCollector.getToolCallbacks()).thenReturn(new ToolCallback[]{weatherCallback});
        when(subAgentFeature.isEnabled()).thenReturn(true);

        String result = orchestrator.runLoop("天气", "原始回复", ctx, mock(SseEmitter.class));

        assertThat(result).as("无任务时应原样返回 currentResponse").isEqualTo("原始回复");
    }

    @Test
    void should_update_response_after_sub_agent_execution() {
        when(planRouter.plan(any(PlanRequest.class)))
                .thenReturn(PlanOutcome.of(List.of(weatherTask()), "ai_plan"));
        when(toolBeanCollector.getToolCallbacks()).thenReturn(new ToolCallback[]{weatherCallback});
        when(subAgentFeature.isEnabled()).thenReturn(true);
        when(roundExecutionProxy.executeRound(anyString(), anyString(), anyList(),
                any(TaskReport.class), anyInt(), any(), any(), any(SseEmitter.class)))
                .thenReturn("北京晴天 25°C");

        String result = orchestrator.runLoop("北京天气", "好的", ctx, mock(SseEmitter.class));

        assertThat(result).as("应返回子 Agent 摘要").isEqualTo("北京晴天 25°C");
    }

    @Test
    void should_stop_after_max_rounds() {
        when(planRouter.plan(any(PlanRequest.class)))
                .thenReturn(PlanOutcome.of(List.of(weatherTask()), "ai_plan"));
        when(toolBeanCollector.getToolCallbacks()).thenReturn(new ToolCallback[]{weatherCallback});
        when(subAgentFeature.isEnabled()).thenReturn(true);
        when(roundExecutionProxy.executeRound(anyString(), anyString(), anyList(),
                any(TaskReport.class), anyInt(), any(), any(), any(SseEmitter.class)))
                .thenReturn("轮次结果");

        String result = orchestrator.runLoop("测试", "初始回复", ctx, mock(SseEmitter.class));

        assertThat(result).as("超过 MAX_ROUNDS 后应返回最后一轮的结果").isNotNull();
        // MAX_ROUNDS=5，每轮子 Agent 路径执行器被调一次
        verify(roundExecutionProxy, atMost(5)).executeRound(anyString(), anyString(), anyList(),
                any(TaskReport.class), anyInt(), any(), any(), any(SseEmitter.class));
    }
}
