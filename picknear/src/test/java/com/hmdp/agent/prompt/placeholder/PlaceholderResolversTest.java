package com.hmdp.agent.prompt.placeholder;

import com.hmdp.agent.context.AgentContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * PlaceholderResolvers 工具类测试。
 */
class PlaceholderResolversTest {

    private AgentContext context;

    @BeforeEach
    void setUp() {
        context = AgentContext.builder()
                .userId(12345L)
                .conversationId("conv_test_001")
                .build();
    }

    @Test
    void should_create_resolver_with_of() {
        // when
        PlaceholderResolver resolver = PlaceholderResolvers.of("testKey", ctx -> "testValue");

        // then
        assertThat(resolver.key()).isEqualTo("testKey");
        assertThat(resolver.resolve(context)).hasValue("testValue");
    }

    @Test
    void should_return_empty_when_function_returns_null() {
        // given
        PlaceholderResolver resolver = PlaceholderResolvers.of("nullKey", ctx -> null);

        // when
        Optional<String> result = resolver.resolve(context);

        // then
        assertThat(result).isEmpty();
    }

    @Test
    void should_return_value_when_function_returns_value() {
        // given
        PlaceholderResolver resolver = PlaceholderResolvers.of("valueKey", ctx -> "hello");

        // when
        Optional<String> result = resolver.resolve(context);

        // then
        assertThat(result).hasValue("hello");
    }

    @Test
    void should_create_constant_resolver() {
        // when
        PlaceholderResolver resolver = PlaceholderResolvers.constant("constantKey", "constantValue");

        // then
        assertThat(resolver.key()).isEqualTo("constantKey");
        assertThat(resolver.resolve(context)).hasValue("constantValue");
    }

    @Test
    void should_ignore_context_in_constant_resolver() {
        // given
        PlaceholderResolver resolver = PlaceholderResolvers.constant("key", "value");

        // when - 传入不同的 context
        AgentContext ctx1 = AgentContext.builder().userId(111L).build();
        AgentContext ctx2 = AgentContext.builder().userId(222L).build();
        Optional<String> result1 = resolver.resolve(ctx1);
        Optional<String> result2 = resolver.resolve(ctx2);

        // then - 值不变
        assertThat(result1).hasValue("value");
        assertThat(result2).hasValue("value");
    }

    @Test
    void should_work_with_lambda_using_context() {
        // given - 创建一个使用 context 的 resolver
        PlaceholderResolver resolver = PlaceholderResolvers.of("userId",
                ctx -> ctx.userId() != null ? String.valueOf(ctx.userId()) : null);

        // when
        Optional<String> result = resolver.resolve(context);

        // then
        assertThat(result).hasValue("12345");
    }

    @Test
    void should_return_empty_when_context_has_no_value() {
        // given - 创建一个没有 userId 的 context
        AgentContext emptyContext = AgentContext.builder().build();
        PlaceholderResolver resolver = PlaceholderResolvers.of("userId",
                ctx -> ctx.userId() != null ? String.valueOf(ctx.userId()) : null);

        // when
        Optional<String> result = resolver.resolve(emptyContext);

        // then
        assertThat(result).isEmpty();
    }

    @Test
    void should_work_with_complex_function() {
        // given - 创建一个复杂的 resolver（计算当前时间）
        PlaceholderResolver resolver = PlaceholderResolvers.of("currentTime",
                ctx -> LocalDateTime.now().withNano(0).toString());

        // when
        Optional<String> result = resolver.resolve(context);

        // then
        assertThat(result).isPresent();
        assertThat(result.get()).matches("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}");
    }

    @Test
    void should_not_create_multiple_classes_for_same_resolver() {
        // given - 多次创建 resolver
        PlaceholderResolver r1 = PlaceholderResolvers.of("key1", ctx -> "v1");
        PlaceholderResolver r2 = PlaceholderResolvers.of("key2", ctx -> "v2");
        PlaceholderResolver r3 = PlaceholderResolvers.of("key3", ctx -> "v3");

        // when & then - 都是同一个类的实例
        assertThat(r1.getClass()).isEqualTo(r2.getClass());
        assertThat(r2.getClass()).isEqualTo(r3.getClass());
        assertThat(r1.getClass().getSimpleName()).isEqualTo("LambdaPlaceholderResolver");
    }
}
