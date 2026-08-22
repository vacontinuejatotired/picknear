package com.hmdp.agent.prompt.placeholder;

import com.hmdp.agent.context.AgentContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 占位符解析器注册中心。
 * <p>
 * Spring 自动收集所有 {@link PlaceholderResolver} 实现，提供统一的解析入口。
 * </p>
 *
 * <h3>功能</h3>
 * <ul>
 *   <li>自动注册：构造器注入 {@code List<PlaceholderResolver>}，Spring 自动发现所有 {@code @Component} 实现</li>
 *   <li>重复检测：相同 key 的 resolver 会抛出 {@link IllegalStateException}，防止静默覆盖</li>
 *   <li>批量解析：{@link #resolveAll(AgentContext)} 解析所有已注册的占位符</li>
 *   <li>单个解析：{@link #resolveSingle(String, AgentContext)} 按需解析指定占位符</li>
 *   <li>动态注册：{@link #register(PlaceholderResolver)} 运行时添加新的 resolver</li>
 * </ul>
 *
 * <h3>使用示例</h3>
 * <pre>{@code
 * @Autowired
 * private PlaceholderResolverRegistry registry;
 *
 * // 解析所有占位符
 * Map<String, Optional<String>> vars = registry.resolveAll(context);
 *
 * // 按需解析单个占位符
 * Optional<String> userId = registry.resolveSingle("userId", context);
 * }</pre>
 */
@Slf4j
@Component
public class PlaceholderResolverRegistry {

    /** key → resolver 映射（线程安全） */
    private final Map<String, PlaceholderResolver> resolvers = new ConcurrentHashMap<>();

    /**
     * 构造器注入：Spring 自动收集所有 {@link PlaceholderResolver} 实现。
     *
     * @param list Spring 注入的 resolver 列表
     * @throws IllegalStateException 如果存在重复的 key
     */
    @Autowired
    public PlaceholderResolverRegistry(List<PlaceholderResolver> list) {
        if (list == null || list.isEmpty()) {
            log.info("[placeholder] 未发现任何 PlaceholderResolver 实现");
            return;
        }
        for (PlaceholderResolver resolver : list) {
            String key = resolver.key();
            PlaceholderResolver existing = resolvers.putIfAbsent(key, resolver);
            if (existing != null) {
                throw new IllegalStateException(
                        "Duplicate placeholder key: '" + key + "'"
                                + " (found in " + existing.getClass().getSimpleName()
                                + " and " + resolver.getClass().getSimpleName() + ")");
            }
            log.debug("[placeholder] 注册 resolver: {} → {}", key, resolver.getClass().getSimpleName());
        }
        log.info("[placeholder] 共注册 {} 个 PlaceholderResolver", resolvers.size());
    }

    /**
     * 获取指定 key 的 resolver。
     *
     * @param key 占位符 key
     * @return resolver 实例，不存在时返回 null
     */
    public PlaceholderResolver get(String key) {
        return resolvers.get(key);
    }

    /**
     * 获取所有已注册的 resolver。
     *
     * @return 不可修改的 resolver 集合
     */
    public Collection<PlaceholderResolver> getAll() {
        return Collections.unmodifiableCollection(resolvers.values());
    }

    /**
     * 获取所有已注册的 key。
     *
     * @return 不可修改的 key 集合
     */
    public Set<String> keys() {
        return Collections.unmodifiableSet(resolvers.keySet());
    }

    /**
     * 解析所有已注册的占位符。
     *
     * @param context Agent 上下文
     * @return key → Optional&lt;value&gt; 映射（包含所有已注册的 key）
     */
    public Map<String, Optional<String>> resolveAll(AgentContext context) {
        Map<String, Optional<String>> result = new LinkedHashMap<>();
        resolvers.forEach((key, resolver) -> {
            try {
                result.put(key, resolver.resolve(context));
            } catch (Exception e) {
                log.warn("[placeholder] 解析失败: key={}, resolver={}, error={}",
                        key, resolver.getClass().getSimpleName(), e.getMessage());
                result.put(key, Optional.empty());
            }
        });
        return result;
    }

    /**
     * 按需解析单个占位符。
     *
     * @param key     占位符 key
     * @param context Agent 上下文
     * @return 解析结果：resolver 不存在或解析无值时返回 {@link Optional#empty()}
     */
    public Optional<String> resolveSingle(String key, AgentContext context) {
        PlaceholderResolver resolver = resolvers.get(key);
        if (resolver == null) {
            log.debug("[placeholder] resolver 不存在: {}", key);
            return Optional.empty();
        }
        try {
            return resolver.resolve(context);
        } catch (Exception e) {
            log.warn("[placeholder] 解析失败: key={}, resolver={}, error={}",
                    key, resolver.getClass().getSimpleName(), e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * 动态注册 resolver（运行时扩展）。
     *
     * @param resolver 要注册的 resolver
     * @throws IllegalStateException 如果 key 已存在
     */
    public void register(PlaceholderResolver resolver) {
        String key = resolver.key();
        PlaceholderResolver existing = resolvers.putIfAbsent(key, resolver);
        if (existing != null) {
            throw new IllegalStateException(
                    "Duplicate placeholder key: '" + key + "'"
                            + " (existing: " + existing.getClass().getSimpleName()
                            + ", new: " + resolver.getClass().getSimpleName() + ")");
        }
        log.debug("[placeholder] 动态注册 resolver: {} → {}", key, resolver.getClass().getSimpleName());
    }
}
