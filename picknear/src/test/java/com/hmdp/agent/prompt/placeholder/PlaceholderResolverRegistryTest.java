package com.hmdp.agent.prompt.placeholder;

import com.hmdp.agent.context.AgentContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * PlaceholderResolverRegistry 测试。
 */
class PlaceholderResolverRegistryTest {

    private AgentContext context;

    @BeforeEach
    void setUp() {
        context = AgentContext.builder()
                .userId(12345L)
                .conversationId("conv_test_001")
                .build();
    }

    @Test
    void should_register_resolvers_on_construction() {
        // given
        List<PlaceholderResolver> resolvers = Arrays.asList(
                createResolver("key1", "value1"),
                createResolver("key2", "value2")
        );

        // when
        PlaceholderResolverRegistry registry = new PlaceholderResolverRegistry(resolvers);

        // then
        assertThat(registry.keys()).containsExactlyInAnyOrder("key1", "key2");
        assertThat(registry.getAll()).hasSize(2);
    }

    @Test
    void should_throw_on_duplicate_key() {
        // given
        List<PlaceholderResolver> resolvers = Arrays.asList(
                createResolver("same_key", "value1"),
                createResolver("same_key", "value2")
        );

        // when & then
        assertThatThrownBy(() -> new PlaceholderResolverRegistry(resolvers))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Duplicate placeholder key")
                .hasMessageContaining("same_key");
    }

    @Test
    void should_handle_empty_resolver_list() {
        // given
        List<PlaceholderResolver> resolvers = Collections.emptyList();

        // when
        PlaceholderResolverRegistry registry = new PlaceholderResolverRegistry(resolvers);

        // then
        assertThat(registry.keys()).isEmpty();
        assertThat(registry.getAll()).isEmpty();
    }

    @Test
    void should_resolve_all() {
        // given
        List<PlaceholderResolver> resolvers = Arrays.asList(
                createResolver("key1", "value1"),
                createResolver("key2", "value2")
        );
        PlaceholderResolverRegistry registry = new PlaceholderResolverRegistry(resolvers);

        // when
        Map<String, Optional<String>> result = registry.resolveAll(context);

        // then
        assertThat(result).hasSize(2);
        assertThat(result.get("key1")).hasValue("value1");
        assertThat(result.get("key2")).hasValue("value2");
    }

    @Test
    void should_resolve_single() {
        // given
        List<PlaceholderResolver> resolvers = Arrays.asList(
                createResolver("key1", "value1"),
                createResolver("key2", "value2")
        );
        PlaceholderResolverRegistry registry = new PlaceholderResolverRegistry(resolvers);

        // when
        Optional<String> result = registry.resolveSingle("key1", context);

        // then
        assertThat(result).hasValue("value1");
    }

    @Test
    void should_return_empty_for_nonexistent_key() {
        // given
        PlaceholderResolverRegistry registry = new PlaceholderResolverRegistry(Collections.emptyList());

        // when
        Optional<String> result = registry.resolveSingle("nonexistent", context);

        // then
        assertThat(result).isEmpty();
    }

    @Test
    void should_get_resolver_by_key() {
        // given
        PlaceholderResolver resolver = createResolver("myKey", "myValue");
        PlaceholderResolverRegistry registry = new PlaceholderResolverRegistry(Collections.singletonList(resolver));

        // when
        PlaceholderResolver found = registry.get("myKey");

        // then
        assertThat(found).isSameAs(resolver);
    }

    @Test
    void should_return_null_for_nonexistent_key_get() {
        // given
        PlaceholderResolverRegistry registry = new PlaceholderResolverRegistry(Collections.emptyList());

        // when
        PlaceholderResolver found = registry.get("nonexistent");

        // then
        assertThat(found).isNull();
    }

    @Test
    void should_dynamically_register_resolver() {
        // given
        PlaceholderResolverRegistry registry = new PlaceholderResolverRegistry(Collections.emptyList());
        PlaceholderResolver newResolver = createResolver("dynamic", "value");

        // when
        registry.register(newResolver);

        // then
        assertThat(registry.get("dynamic")).isSameAs(newResolver);
        assertThat(registry.keys()).containsExactly("dynamic");
    }

    @Test
    void should_throw_on_duplicate_dynamic_registration() {
        // given
        PlaceholderResolver existing = createResolver("key", "existing");
        PlaceholderResolverRegistry registry = new PlaceholderResolverRegistry(Collections.singletonList(existing));
        PlaceholderResolver duplicate = createResolver("key", "new");

        // when & then
        assertThatThrownBy(() -> registry.register(duplicate))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Duplicate placeholder key")
                .hasMessageContaining("key");
    }

    @Test
    void should_handle_resolver_exception_gracefully_in_resolve_all() {
        // given
        PlaceholderResolver goodResolver = createResolver("good", "value");
        PlaceholderResolver badResolver = createResolver("bad", ctx -> {
            throw new RuntimeException("test error");
        });
        PlaceholderResolverRegistry registry = new PlaceholderResolverRegistry(Arrays.asList(goodResolver, badResolver));

        // when
        Map<String, Optional<String>> result = registry.resolveAll(context);

        // then
        assertThat(result).hasSize(2);
        assertThat(result.get("good")).hasValue("value");
        assertThat(result.get("bad")).isEmpty(); // 异常时返回 empty
    }

    @Test
    void should_handle_resolver_exception_gracefully_in_resolve_single() {
        // given
        PlaceholderResolver badResolver = createResolver("bad", ctx -> {
            throw new RuntimeException("test error");
        });
        PlaceholderResolverRegistry registry = new PlaceholderResolverRegistry(Collections.singletonList(badResolver));

        // when
        Optional<String> result = registry.resolveSingle("bad", context);

        // then
        assertThat(result).isEmpty(); // 异常时返回 empty
    }

    // ========== 辅助方法 ==========

    private PlaceholderResolver createResolver(String key, String value) {
        return PlaceholderResolvers.of(key, ctx -> value);
    }

    private PlaceholderResolver createResolver(String key, java.util.function.Function<AgentContext, String> fn) {
        return PlaceholderResolvers.of(key, fn);
    }
}
