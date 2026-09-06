package com.hmdp.agent.plan.executionPlan.binding;

import com.hmdp.agent.plan.executionPlan.annotation.FromTool;
import com.hmdp.agent.plan.executionPlan.model.ToolMetadata;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 参数来源匹配优先级与歧义校验测试。
 */
class ToolParameterBindingPlanFactoryTest {

    private final ToolParameterBindingPlanFactory factory = new ToolParameterBindingPlanFactory();

    @Test
    void create_uniqueReturnType_shouldBindParameter() throws Exception {
        ToolParameterBindingPlan plan = factory.create(
            List.of("sourceA", "combine"),
            metadataProvider("sourceA", method("sourceA"), List.of(), "combine",
                method("consumeByType", SamplePayload.class), List.of("sourceA")));

        assertThat(plan.isValid()).isTrue();
        assertThat(plan.bindingsFor("combine")).containsExactly(
            new ToolParameterBinding("payload", "sourceA", ParameterSource.RETURN_TYPE));
    }

    @Test
    void create_fromTool_shouldOverrideAmbiguousReturnType() throws Exception {
        ToolParameterBindingPlan plan = factory.create(
            List.of("sourceA", "sourceB", "combine"),
            metadataProvider("sourceA", method("sourceA"), List.of(),
                "sourceB", method("sourceB"), List.of(),
                "combine", method("consumeExplicit", SamplePayload.class),
                List.of("sourceA", "sourceB")));

        assertThat(plan.isValid()).isTrue();
        assertThat(plan.bindingsFor("combine")).containsExactly(
            new ToolParameterBinding("payload", "sourceB", ParameterSource.FROM_TOOL));
    }

    @Test
    void create_ambiguousReturnType_withoutFromTool_shouldBeRejected() throws Exception {
        ToolParameterBindingPlan plan = factory.create(
            List.of("sourceA", "sourceB", "combine"),
            metadataProvider("sourceA", method("sourceA"), List.of(),
                "sourceB", method("sourceB"), List.of(),
                "combine", method("consumeByType", SamplePayload.class),
                List.of("sourceA", "sourceB")));

        assertThat(plan.isValid()).isFalse();
        assertThat(plan.issues()).singleElement().satisfies(issue -> {
            assertThat(issue.targetToolName()).isEqualTo("combine");
            assertThat(issue.message()).contains("歧义").contains("sourceA").contains("sourceB");
        });
        assertThat(plan.bindingsFor("combine")).isEmpty();
    }

    @Test
    void create_parameterNameMatch_shouldBeFallbackSourceRule() throws Exception {
        ToolParameterBindingPlan plan = factory.create(
            List.of("sourceA", "combine"),
            metadataProvider("sourceA", method("sourceA"), List.of(), "combine",
                method("consumeByName", String.class), List.of("sourceA")));

        assertThat(plan.isValid()).isTrue();
        assertThat(plan.bindingsFor("combine")).containsExactly(
            new ToolParameterBinding("sourceA", "sourceA", ParameterSource.PARAMETER_NAME));
    }

    @Test
    void create_fromToolSourceOutsideDependsOn_shouldBeRejected() throws Exception {
        ToolParameterBindingPlan plan = factory.create(
            List.of("sourceA", "combine"),
            metadataProvider("sourceA", method("sourceA"), List.of(), "combine",
                method("consumeUndeclared", SamplePayload.class), List.of("other")));

        assertThat(plan.isValid()).isFalse();
        assertThat(plan.issues()).singleElement().satisfies(issue ->
            assertThat(issue.message()).contains("@FromTool").contains("未在 @DependsOn"));
    }

    @Test
    void create_explicitSourceTypeMismatch_shouldBeRejected() throws Exception {
        ToolParameterBindingPlan plan = factory.create(
            List.of("sourceC", "combine"),
            metadataProvider("sourceC", method("sourceC"), List.of(), "combine",
                method("consumeMismatched", SamplePayload.class), List.of("sourceC")));

        assertThat(plan.isValid()).isFalse();
        assertThat(plan.issues()).singleElement().satisfies(issue ->
            assertThat(issue.message()).contains("不兼容"));
    }

    private Map<String, ToolMetadata> metadata(Object... entries) throws Exception {
        Map<String, ToolMetadata> result = new LinkedHashMap<>();
        for (int i = 0; i < entries.length; i += 3) {
            String name = (String) entries[i];
            Method method = (Method) entries[i + 1];
            @SuppressWarnings("unchecked")
            List<String> dependencies = (List<String>) entries[i + 2];
            result.put(name, ToolMetadata.builder()
                .name(name)
                .method(method)
                .returnType(method.getReturnType())
                .dependencies(dependencies)
                .build());
        }
        return result;
    }

    private java.util.function.Function<String, ToolMetadata> metadataProvider(
            Object... entries) throws Exception {
        Map<String, ToolMetadata> map = metadata(entries);
        return map::get;
    }

    private Method method(String name, Class<?>... parameterTypes) throws Exception {
        return BindingFixture.class.getDeclaredMethod(name, parameterTypes);
    }

    static class BindingFixture {

        SamplePayload sourceA() {
            return new SamplePayload(1L);
        }

        SamplePayload sourceB() {
            return new SamplePayload(2L);
        }

        OtherPayload sourceC() {
            return new OtherPayload("c");
        }

        String consumeByType(SamplePayload payload) {
            return "";
        }

        String consumeExplicit(@FromTool("sourceB") SamplePayload payload) {
            return "";
        }

        String consumeByName(String sourceA) {
            return "";
        }

        String consumeUndeclared(@FromTool("sourceA") SamplePayload payload) {
            return "";
        }

        String consumeMismatched(@FromTool("sourceC") SamplePayload payload) {
            return "";
        }
    }

    record SamplePayload(Long id) {}

    record OtherPayload(String code) {}
}
