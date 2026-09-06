package com.hmdp.agent.plan.executionPlan;

import com.hmdp.agent.plan.executionPlan.annotation.FromTool;
import com.hmdp.agent.plan.executionPlan.model.ToolMetadata;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.lang.reflect.Method;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/**
 * PlanGenerator 分层/校验逻辑单测（DAG 不依赖参数注入，可独立验证）。
 */
@ExtendWith(MockitoExtension.class)
class PlanGeneratorTest {

    @Mock
    private GraphAnalyzer graphAnalyzer;

    @InjectMocks
    private PlanGenerator planGenerator;

    @Test
    void plan_independentTools_shouldCollapseIntoSingleLayer() {
        Map<String, List<String>> graph = graph("a", "b", "c");
        stubGraphAndMetadata(graph);

        ExecutionPlan plan = planGenerator.plan(List.of("a", "b", "c"));

        assertThat(plan.isValid()).isTrue();
        assertThat(plan.getLayers()).hasSize(1);
        assertThat(plan.getLayers().get(0)).containsExactlyInAnyOrder("a", "b", "c");
        assertThat(plan.getExecutionOrder()).containsExactlyInAnyOrder("a", "b", "c");
    }

    @Test
    void plan_chainDependency_shouldProduceOneLayerPerDepth() {
        Map<String, List<String>> graph = new LinkedHashMap<>();
        graph.put("a", List.of());
        graph.put("b", List.of("a"));
        graph.put("c", List.of("b"));
        stubGraphAndMetadata(graph);

        ExecutionPlan plan = planGenerator.plan(List.of("a", "b", "c"));

        assertThat(plan.isValid()).isTrue();
        assertThat(plan.getLayers()).containsExactly(
                List.of("a"),
                List.of("b"),
                List.of("c"));
    }

    @Test
    void plan_diamondDependency_shouldParallelizeMiddleLayer() {
        Map<String, List<String>> graph = new LinkedHashMap<>();
        graph.put("a", List.of());
        graph.put("b", List.of("a"));
        graph.put("c", List.of("a"));
        graph.put("d", List.of("b", "c"));
        stubGraphAndMetadata(graph);

        ExecutionPlan plan = planGenerator.plan(List.of("a", "b", "c", "d"));

        assertThat(plan.isValid()).isTrue();
        assertThat(plan.getLayers()).hasSize(3);
        assertThat(plan.getLayers().get(0)).containsExactly("a");
        assertThat(plan.getLayers().get(1)).containsExactlyInAnyOrder("b", "c");
        assertThat(plan.getLayers().get(2)).containsExactly("d");
    }

    @Test
    void plan_cycleDependency_shouldBeRejected() {
        Map<String, List<String>> graph = new LinkedHashMap<>();
        graph.put("a", List.of("b"));
        graph.put("b", List.of("a"));
        when(graphAnalyzer.buildGraph(List.of("a", "b")))
                .thenReturn(GraphBuildResult.builder().graph(graph).build());

        ExecutionPlan plan = planGenerator.plan(List.of("a", "b"));

        assertThat(plan.isValid()).isFalse();
        assertThat(plan.getInvalidReason()).contains("循环");
    }

    @Test
    void plan_unknownTool_shouldBeRejected() {
        when(graphAnalyzer.buildGraph(List.of("a", "ghost")))
                .thenReturn(GraphBuildResult.builder()
                        .graph(graph("a"))
                        .unknownTools(List.of("ghost"))
                        .build());

        ExecutionPlan plan = planGenerator.plan(List.of("a", "ghost"));

        assertThat(plan.isValid()).isFalse();
        assertThat(plan.getInvalidReason()).contains("未知工具");
        assertThat(plan.getUnknownTools()).containsExactly("ghost");
    }

    @Test
    void plan_missingDependency_shouldBeRejected() {
        Map<String, List<String>> graph = new LinkedHashMap<>();
        graph.put("a", List.of("b"));
        when(graphAnalyzer.buildGraph(List.of("a")))
                .thenReturn(GraphBuildResult.builder().graph(graph).build());

        ExecutionPlan plan = planGenerator.plan(List.of("a"));

        assertThat(plan.isValid()).isFalse();
        assertThat(plan.getInvalidReason()).contains("未被选中");
    }

    @Test
    void plan_sequentialOnlyTool_shouldStayAloneInOwnLayer() {
        Map<String, List<String>> graph = new LinkedHashMap<>();
        graph.put("lock", List.of());
        graph.put("fast", List.of());
        stubGraphAndMetadata(graph);
        lenient().when(graphAnalyzer.getMetadata("lock"))
                .thenReturn(metadata("lock", true));

        ExecutionPlan plan = planGenerator.plan(List.of("lock", "fast"));

        assertThat(plan.isValid()).isTrue();
        assertThat(plan.getLayers()).hasSize(2);
        assertThat(plan.getLayers().get(0)).containsExactly("lock");
        assertThat(plan.getLayers().get(1)).containsExactly("fast");
    }

    @Test
    void plan_dependencyBinding_shouldBeAttachedToExecutionPlan() throws Exception {
        Map<String, List<String>> graph = new LinkedHashMap<>();
        graph.put("sourceA", List.of());
        graph.put("combine", List.of("sourceA"));
        when(graphAnalyzer.buildGraph(any()))
                .thenReturn(GraphBuildResult.builder().graph(graph).build());
        when(graphAnalyzer.getMetadata("sourceA")).thenReturn(toolMetadata(
                "sourceA", BindingFixture.class.getDeclaredMethod("sourceA"), List.of()));
        when(graphAnalyzer.getMetadata("combine")).thenReturn(toolMetadata(
                "combine", BindingFixture.class.getDeclaredMethod(
                        "consumeByType", BindingFixture.SamplePayload.class),
                List.of("sourceA")));

        ExecutionPlan plan = planGenerator.plan(List.of("sourceA", "combine"));

        assertThat(plan.isValid()).isTrue();
        assertThat(plan.getParameterBindings().bindingsFor("combine")).hasSize(1);
    }

    @Test
    void plan_ambiguousParameterBinding_shouldRejectPlan() throws Exception {
        Map<String, List<String>> graph = new LinkedHashMap<>();
        graph.put("sourceA", List.of());
        graph.put("sourceB", List.of());
        graph.put("combine", List.of("sourceA", "sourceB"));
        when(graphAnalyzer.buildGraph(any()))
                .thenReturn(GraphBuildResult.builder().graph(graph).build());
        when(graphAnalyzer.getMetadata("sourceA")).thenReturn(toolMetadata(
                "sourceA", BindingFixture.class.getDeclaredMethod("sourceA"), List.of()));
        when(graphAnalyzer.getMetadata("sourceB")).thenReturn(toolMetadata(
                "sourceB", BindingFixture.class.getDeclaredMethod("sourceB"), List.of()));
        when(graphAnalyzer.getMetadata("combine")).thenReturn(toolMetadata(
                "combine", BindingFixture.class.getDeclaredMethod(
                        "consumeByType", BindingFixture.SamplePayload.class),
                List.of("sourceA", "sourceB")));

        ExecutionPlan plan = planGenerator.plan(List.of("sourceA", "sourceB", "combine"));

        assertThat(plan.isValid()).isFalse();
        assertThat(plan.getInvalidReason()).contains("歧义");
    }

    @Test
    void plan_fromToolResolvesAmbiguity_shouldRemainValid() throws Exception {
        Map<String, List<String>> graph = new LinkedHashMap<>();
        graph.put("sourceA", List.of());
        graph.put("sourceB", List.of());
        graph.put("combine", List.of("sourceA", "sourceB"));
        when(graphAnalyzer.buildGraph(any()))
                .thenReturn(GraphBuildResult.builder().graph(graph).build());
        when(graphAnalyzer.getMetadata("sourceA")).thenReturn(toolMetadata(
                "sourceA", BindingFixture.class.getDeclaredMethod("sourceA"), List.of()));
        when(graphAnalyzer.getMetadata("sourceB")).thenReturn(toolMetadata(
                "sourceB", BindingFixture.class.getDeclaredMethod("sourceB"), List.of()));
        when(graphAnalyzer.getMetadata("combine")).thenReturn(toolMetadata(
                "combine", BindingFixture.class.getDeclaredMethod(
                        "consumeExplicit", BindingFixture.SamplePayload.class),
                List.of("sourceA", "sourceB")));

        ExecutionPlan plan = planGenerator.plan(List.of("sourceA", "sourceB", "combine"));

        assertThat(plan.isValid()).isTrue();
        assertThat(plan.getParameterBindings().bindingsFor("combine"))
                .singleElement()
                .satisfies(binding -> {
                    assertThat(binding.sourceToolName()).isEqualTo("sourceB");
                    assertThat(binding.parameterName()).isEqualTo("payload");
                });
    }

    private ToolMetadata toolMetadata(String name, Method method, List<String> dependencies) {
        return ToolMetadata.builder()
                .name(name)
                .method(method)
                .returnType(method.getReturnType())
                .dependencies(dependencies)
                .build();
    }

    private static class BindingFixture {

        SamplePayload sourceA() {
            return new SamplePayload(1L);
        }

        SamplePayload sourceB() {
            return new SamplePayload(2L);
        }

        String consumeByType(SamplePayload payload) {
            return "";
        }

        String consumeExplicit(@FromTool("sourceB") SamplePayload payload) {
            return "";
        }

        record SamplePayload(Long id) {}
    }

    private void stubGraphAndMetadata(Map<String, List<String>> graph) {
        when(graphAnalyzer.buildGraph(any())).thenReturn(
                GraphBuildResult.builder().graph(graph).build());
        for (String toolName : graph.keySet()) {
            lenient().when(graphAnalyzer.getMetadata(toolName))
                    .thenReturn(metadata(toolName, false));
        }
    }

    private Map<String, List<String>> graph(String... tools) {
        Map<String, List<String>> graph = new LinkedHashMap<>();
        for (String tool : tools) {
            graph.put(tool, List.of());
        }
        return graph;
    }

    private ToolMetadata metadata(String name, boolean sequentialOnly) {
        return ToolMetadata.builder()
                .name(name)
                .returnType(Object.class)
                .sequentialOnly(sequentialOnly)
                .build();
    }
}
