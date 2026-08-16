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
}
