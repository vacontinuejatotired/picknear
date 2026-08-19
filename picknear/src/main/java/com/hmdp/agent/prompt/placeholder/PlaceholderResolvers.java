package com.hmdp.agent.prompt.placeholder;

import com.hmdp.agent.context.AgentContext;

import java.util.Optional;
import java.util.function.Function;

/**
 * 占位符解析器工具类。
 * <p>
 * 提供工厂方法，用于快速创建 {@link PlaceholderResolver} 实例，无需编写单独的实现类。
 * </p>
 *
 * <h3>使用示例</h3>
 * <pre>{@code
 * // 快速创建简单 resolver
 * PlaceholderResolver resolver = PlaceholderResolvers.of("currentTime",
 *     ctx -> LocalDateTime.now().toString());
 *
 * // 创建依赖上下文的 resolver
 * PlaceholderResolver resolver = PlaceholderResolvers.of("userId",
 *     ctx -> ctx.userId() != null ? String.valueOf(ctx.userId()) : null);
 * }</pre>
 *
 * <h3>性能说明</h3>
 * <p>
 * 使用命名内部类 {@link LambdaPlaceholderResolver} 替代匿名类，
 * 大量使用时不会生成大量匿名类文件。
 * </p>
 */
public final class PlaceholderResolvers {

    private PlaceholderResolvers() {
    }

    /**
     * 快速创建占位符解析器。
     *
     * @param key  占位符 key（不含 {@code {{}}）
     * @param fn   解析函数：接收 {@link AgentContext}，返回值（null 表示无值）
     * @return 解析器实例
     */
    public static PlaceholderResolver of(String key, Function<AgentContext, String> fn) {
        return new LambdaPlaceholderResolver(key, fn);
    }

    /**
     * 创建常量值解析器（忽略上下文，返回固定值）。
     *
     * @param key   占位符 key
     * @param value 固定值
     * @return 解析器实例
     */
    public static PlaceholderResolver constant(String key, String value) {
        return new LambdaPlaceholderResolver(key, ctx -> value);
    }

    /**
     * 创建可空值解析器（值为 null 时返回 Optional.empty()）。
     *
     * @param key  占位符 key
     * @param fn   解析函数：接收 {@link AgentContext}，返回可能为 null 的值
     * @return 解析器实例
     */
    public static PlaceholderResolver nullable(String key, Function<AgentContext, String> fn) {
        return new LambdaPlaceholderResolver(key, fn);
    }

    /**
     * 基于 Lambda 的解析器实现（命名内部类，避免匿名类膨胀）。
     */
    private static final class LambdaPlaceholderResolver implements PlaceholderResolver {

        private final String key;
        private final Function<AgentContext, String> fn;

        LambdaPlaceholderResolver(String key, Function<AgentContext, String> fn) {
            this.key = key;
            this.fn = fn;
        }

        @Override
        public String key() {
            return key;
        }

        @Override
        public Optional<String> resolve(AgentContext context) {
            String value = fn.apply(context);
            return Optional.ofNullable(value);
        }
    }
}
