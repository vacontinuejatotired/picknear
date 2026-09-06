package com.hmdp.agent.execution.loop;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hmdp.agent.execution.loop.argument.ToolCallArgumentInjector;
import com.hmdp.agent.plan.executionPlan.binding.ParameterSource;
import com.hmdp.agent.plan.executionPlan.binding.ToolParameterBinding;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * DagToolInvokerFactory 装配测试：最终传给 ToolCallback 的 payload 必须已注入上游结果。
 */
class DagToolInvokerFactoryTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void create_shouldCallCallbackWithEnrichedPayload() throws Exception {
        InMemoryToolResultStore store = new InMemoryToolResultStore();
        store.store("sourceA", "{\"id\":1}", Object.class);
        ToolCallArgumentInjector injector = new ToolCallArgumentInjector(store, objectMapper);
        DagToolInvokerFactory factory = new DagToolInvokerFactory(injector);

        ToolCallback callback = mock(ToolCallback.class);
        when(callback.call(anyString(), any())).thenReturn("{\"ok\":true}");
        List<ToolParameterBinding> bindings = List.of(
            new ToolParameterBinding("payload", "sourceA", ParameterSource.RETURN_TYPE));

        Object result = factory.create(
                "combine", "{\"city\":\"北京\"}", callback,
                new ToolContext(Map.of()), bindings)
            .invoke();

        assertThat(result).isEqualTo("{\"ok\":true}");
        ArgumentCaptor<String> payloadCaptor = ArgumentCaptor.forClass(String.class);
        verify(callback).call(payloadCaptor.capture(), any());
        JsonNode node = objectMapper.readTree(payloadCaptor.getValue());
        assertThat(node.path("city").asText()).isEqualTo("北京");
        assertThat(node.path("payload").path("id").asLong()).isEqualTo(1L);
    }
}
