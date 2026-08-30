package com.hmdp.agent.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 工具业务元数据注解 — 工具注册表单一事实源（配合 {@code ToolRegistry}）。
 * <p>
 * 标注在 {@link TargetTool} 工具类的 {@code @Tool} 方法上（与 {@code @Tool} 同标），
 * 声明该工具的过滤/路由元数据。新增工具只需在方法上补注解，无需再改
 * CompactCatalogBuilder.TRIGGER_KEYWORDS / ToolIntentTree.NODES / PromptSeeder.TOOL_NAMES。
 * </p>
 *
 * <pre>{@code
 * @TargetTool(active = true)
 * public class WeatherQueryTool {
 *     @Tool(description = "查询天气")
 *     @ToolMeta(keywords = {"天气", "气温"}, intents = {"weather"})
 *     public String queryWeather(ToolContext ctx) { ... }
 * }
 * }</pre>
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface ToolMeta {

    /**
     * 触发关键词：用户输入命中任一词时，该工具才列入紧凑目录。
     * 空数组 = 保守放行（不参与关键词过滤）。
     */
    String[] keywords() default {};

    /**
     * 意图树归属业务节点 id（如 {@code "blog"}、{@code "shop"}）：跨组工具可多填
     * （如 {@code queryVouchersByShop} 同时属于 shop 与 voucher）。
     */
    String[] intents() default {};

    // ==================== 异常重试相关 ====================

    /**
     * 操作是否幂等。
     * <p>
     * 幂等操作在异常时可以安全重试（如查询、幂等写入）；
     * 非幂等操作不应重试（如余额扣减、订单创建、红包发送）。
     * </p>
     * <p>判断标准：如果操作执行了一半（成功但响应丢失），再执行一次是否安全？</p>
     * <ul>
     *   <li>安全 → idempotent = true（默认）</li>
     *   <li>不安全 → idempotent = false</li>
     * </ul>
     *
     * @see #maxRetries()
     * @see #retryOnTimeout()
     */
    boolean idempotent() default true;

    /**
     * 工具级最大重试次数。
     * <p>
     * -1 表示使用全局配置（{@code agent.subtask.dag.default-max-retries}）；
     * 0 表示禁止重试；
     * 正数表示使用指定值。
     * </p>
     */
    int maxRetries() default -1;

    /**
     * 超时时是否允许重试。
     * <p>
     * -1 表示跟随 idempotent 设置（幂等操作超时可重试，非幂等不可）；
     * 0 表示超时也不重试（即使操作幂等）；
     * 1 表示超时可重试（即使操作非幂等，慎用）。
     * </p>
     * <p>
     * 注意：此属性优先级高于 idempotent，用于处理"操作可能已执行但不确定"的场景。
     * </p>
     */
    int retryOnTimeout() default -1;
}
