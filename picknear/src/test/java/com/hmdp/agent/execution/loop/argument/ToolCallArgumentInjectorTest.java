package com.hmdp.agent.execution.loop.argument;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hmdp.agent.execution.loop.InMemoryToolResultStore;
import com.hmdp.agent.plan.executionPlan.binding.ParameterSource;
import com.hmdp.agent.plan.executionPlan.binding.ToolParameterBinding;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 工具调用参数注入测试：链式、菱形、null 语义、跨层与 fail-safe。
 */
class ToolCallArgumentInjectorTest {

    private final InMemoryToolResultStore store = new InMemoryToolResultStore();
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final ToolCallArgumentInjector injector =
        new ToolCallArgumentInjector(store, objectMapper);

    @Test
    void inject_noBindings_shouldKeepOriginalPayloadUntouched() {
        assertThat(injector.inject("tool", "not-json", List.of()))
            .isEqualTo("not-json");
    }

    @Test
    void inject_chainBinding_shouldMergeUpstreamResult() throws Exception {
        store.store("sourceA", "{\"id\":1,\"name\":\"shop-a\"}", Object.class);

        String result = injector.inject("combine", "{\"city\":\"北京\"}",
            List.of(binding("payload", "sourceA")));

        JsonNode node = read(result);
        assertThat(node.path("city").asText()).isEqualTo("北京");
        assertThat(node.path("payload").path("id").asLong()).isEqualTo(1L);
        assertThat(node.path("payload").path("name").asText()).isEqualTo("shop-a");
    }

    @Test
    void inject_diamondTwoUpstreams_shouldInjectBothParameters() throws Exception {
        store.store("sourceA", "{\"id\":1}", Object.class);
        store.store("sourceB", "{\"code\":\"b\"}", Object.class);

        String result = injector.inject("combine", "{}",
            List.of(
                binding("payloadA", "sourceA"),
                binding("payloadB", "sourceB")));

        JsonNode node = read(result);
        assertThat(node.path("payloadA").path("id").asLong()).isEqualTo(1L);
        assertThat(node.path("payloadB").path("code").asText()).isEqualTo("b");
    }

    @Test
    void inject_dependencyBinding_shouldOverrideSameNameLlmArgument() throws Exception {
        store.store("sourceA", "{\"id\":9}", Object.class);

        String result = injector.inject("combine", "{\"payload\":{\"id\":7}}",
            List.of(binding("payload", "sourceA")));

        assertThat(read(result).path("payload").path("id").asLong()).isEqualTo(9L);
    }

    @Test
    void inject_stringUpstreamResult_shouldNotDoubleQuoteText() throws Exception {
        store.store("sourceA", "\"hello\"", Object.class);

        String result = injector.inject("combine", "{}",
            List.of(binding("text", "sourceA")));

        assertThat(read(result).path("text").asText()).isEqualTo("hello");
        assertThat(result).contains("\"text\":\"hello\"");
    }

    @Test
    void inject_upstreamNull_shouldInjectJsonNullAndContinue() throws Exception {
        store.store("sourceA", null, Object.class);

        String result = injector.inject("combine", "{}",
            List.of(binding("payload", "sourceA")));

        assertThat(read(result).path("payload").isNull()).isTrue();
    }

    @Test
    void inject_upstreamJsonNullString_shouldAlsoInjectNull() throws Exception {
        store.store("sourceA", "null", Object.class);

        String result = injector.inject("combine", "{}",
            List.of(binding("payload", "sourceA")));

        assertThat(read(result).path("payload").isNull()).isTrue();
    }

    @Test
    void inject_afterCurrentLayerCleared_shouldReadCrossLayerResult() throws Exception {
        store.store("sourceA", "{\"id\":5}", Object.class);
        store.clearCurrentLayer();

        String result = injector.inject("combine", "{}",
            List.of(binding("payload", "sourceA")));

        assertThat(read(result).path("payload").path("id").asLong()).isEqualTo(5L);
    }

    @Test
    void inject_malformedLlmArguments_shouldFailWithReadableMessage() {
        store.store("sourceA", "{}", Object.class);

        assertThatThrownBy(() ->
            injector.inject("combine", "{broken", List.of(binding("payload", "sourceA"))))
            .isInstanceOf(ToolCallArgumentInjectionException.class)
            .hasMessageContaining("combine")
            .hasMessageContaining("不是合法 JSON");
    }

    @Test
    void inject_malformedUpstreamResult_shouldNotSilentlyFallback() {
        store.store("sourceA", "not-json", Object.class);

        assertThatThrownBy(() ->
            injector.inject("combine", "{}", List.of(binding("payload", "sourceA"))))
            .isInstanceOf(ToolCallArgumentInjectionException.class)
            .hasMessageContaining("payload")
            .hasMessageContaining("不是合法 JSON");
    }

    private ToolParameterBinding binding(String parameterName, String sourceTool) {
        return new ToolParameterBinding(
            parameterName, sourceTool, ParameterSource.RETURN_TYPE);
    }

    private JsonNode read(String json) throws Exception {
        return objectMapper.readTree(json);
    }
}
